package com.marketai.mf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketai.mf.entity.MfNavHistory;
import com.marketai.mf.repository.MfNavHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Historical NAV ingestion from MFAPI.in — a free, key-less mirror of AMFI's per-scheme NAV
 * archive, typically covering the whole life of the scheme.
 *
 * <p>AMFI's own NAVAll.txt only carries *today's* NAV, so trailing returns and volatility were
 * previously not computable from anything we stored. This fills that gap.
 *
 * <p>Uses the same {@code WebClient.Builder} injection as {@code AmfiNavService} and
 * {@code YahooFinanceClient} rather than introducing another HTTP stack.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MfNavHistoryService {

    private static final String HISTORY_URL = "https://api.mfapi.in/mf/{schemeCode}";
    private static final DateTimeFormatter MFAPI_DATE =
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH);

    private final WebClient.Builder webClientBuilder;
    private final MfNavHistoryRepository navHistoryRepository;

    /**
     * Fetch the full NAV history for a scheme and store only the dates we don't already have.
     * Idempotent — running twice inserts nothing the second time.
     *
     * @return number of new rows stored; 0 if the scheme is unknown, the call failed, or we
     *         were already up to date. Never throws: a dead upstream must not break a caller
     *         (notably the nightly refresh loop).
     */
    @Transactional
    public int fetchAndStoreHistory(String schemeCode) {
        if (schemeCode == null || schemeCode.trim().isEmpty()) return 0;
        String code = schemeCode.trim();

        List<NavPoint> points = fetchHistory(code);
        if (points.isEmpty()) return 0;

        Set<LocalDate> existing = new HashSet<>(navHistoryRepository.findDatesBySchemeCode(code));
        List<MfNavHistory> toInsert = new ArrayList<>();
        for (NavPoint p : points) {
            if (existing.contains(p.date)) continue;
            existing.add(p.date); // MFAPI has been observed to repeat a date; don't insert it twice
            toInsert.add(MfNavHistory.builder()
                    .schemeCode(code)
                    .date(p.date)
                    .nav(p.nav)
                    .build());
        }

        if (toInsert.isEmpty()) {
            log.debug("MF NAV history for {} already up to date ({} rows)", code, points.size());
            return 0;
        }
        navHistoryRepository.saveAll(toInsert);
        log.info("MF NAV history {}: stored {} new of {} points returned", code, toInsert.size(), points.size());
        return toInsert.size();
    }

    /**
     * Raw fetch + parse, with no persistence. Returns an empty list on any network or parse
     * failure — an empty history is reported honestly as "no data", never padded.
     */
    List<NavPoint> fetchHistory(String schemeCode) {
        List<NavPoint> out = new ArrayList<>();
        try {
            JsonNode root = webClientBuilder.build().get()
                    .uri(HISTORY_URL, schemeCode)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (root == null) {
                log.warn("MFAPI returned no body for scheme {}", schemeCode);
                return out;
            }
            JsonNode data = root.at("/data");
            if (!data.isArray() || data.size() == 0) {
                log.warn("MFAPI returned no NAV history for scheme {}", schemeCode);
                return out;
            }

            int skipped = 0;
            for (JsonNode point : data) {
                String dateText = point.at("/date").asText(null);
                String navText = point.at("/nav").asText(null);
                if (dateText == null || navText == null) { skipped++; continue; }
                try {
                    BigDecimal nav = new BigDecimal(navText.trim());
                    // MFAPI emits "0" / "0.00000" for days a scheme wasn't yet priced. A zero
                    // NAV is not a real observation and would wreck any return calculation.
                    if (nav.compareTo(BigDecimal.ZERO) <= 0) { skipped++; continue; }
                    out.add(new NavPoint(LocalDate.parse(dateText.trim(), MFAPI_DATE), nav));
                } catch (Exception malformed) {
                    skipped++;
                }
            }
            if (skipped > 0) {
                log.debug("MFAPI scheme {}: skipped {} unusable NAV points", schemeCode, skipped);
            }
        } catch (Exception e) {
            log.warn("MF NAV history fetch failed for scheme {}: {}", schemeCode, e.getMessage());
            return new ArrayList<>();
        }
        return out;
    }

    static class NavPoint {
        final LocalDate date;
        final BigDecimal nav;
        NavPoint(LocalDate date, BigDecimal nav) { this.date = date; this.nav = nav; }
    }
}
