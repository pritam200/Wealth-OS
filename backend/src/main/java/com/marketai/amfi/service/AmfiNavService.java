package com.marketai.amfi.service;

import com.marketai.amfi.dto.AmfiNavResult;
import com.marketai.amfi.dto.MfCategoryBucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real MF NAV data source — AMFI (Association of Mutual Funds in India) publishes every
 * scheme's official daily NAV as a free public text file, no API key or auth needed. This
 * replaces "mutual funds have no live feed, NAV is manually entered" with an actual source,
 * refreshed once a day and held in memory (AMFI's file is small, ~1-2MB of plain text).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AmfiNavService {

    private static final String NAV_URL = "https://www.amfiindia.com/spages/NAVAll.txt";
    private static final DateTimeFormatter AMFI_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final WebClient.Builder webClientBuilder;
    private final AtomicReference<List<AmfiNavResult>> cache = new AtomicReference<>(Collections.emptyList());

    @PostConstruct
    void init() {
        refresh();
    }

    @Scheduled(cron = "0 30 21 * * *") // AMFI publishes the day's NAV in the evening
    public void refresh() {
        try {
            String raw = webClientBuilder.build().get().uri(NAV_URL)
                .header("User-Agent", "Mozilla/5.0")
                .retrieve().bodyToMono(String.class).block();
            if (raw == null) return;
            List<AmfiNavResult> parsed = parse(raw);
            if (!parsed.isEmpty()) {
                cache.set(parsed);
                log.info("AMFI NAV refresh: loaded {} schemes", parsed.size());
            }
        } catch (Exception e) {
            log.warn("AMFI NAV fetch failed: {}", e.getMessage());
        }
    }

    /**
     * NAVAll.txt is not flat CSV — it is sectioned. Semicolon-free lines carry the SEBI
     * category and the fund house, and every scheme row below them belongs to that pair:
     *
     * <pre>
     * Open Ended Schemes(Equity Scheme - Large Cap Fund)   &lt;- category line
     * SBI Mutual Fund                                      &lt;- AMC line
     * 119598;INF209K01157;-;SBI Blue Chip Fund…;85.43;07-Aug-2026
     * </pre>
     *
     * Those two header kinds used to be dropped, which is why category/AMC were unavailable
     * despite already being downloaded. We now carry the most recent of each forward.
     */
    List<AmfiNavResult> parse(String raw) {
        List<AmfiNavResult> out = new ArrayList<>();
        String category = null;
        String schemeType = null;
        String amc = null;

        for (String rawLine : raw.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.indexOf(';') < 0) {
                // A section header: either the category or the fund house.
                if (isCategoryLine(line)) {
                    category = extractCategory(line);
                    schemeType = extractSchemeType(line);
                    // A new category section always re-declares its AMC. Clearing here stops a
                    // previous section's fund house leaking onto rows it doesn't own.
                    amc = null;
                } else {
                    amc = line;
                }
                continue;
            }

            String[] f = line.split(";");
            if (f.length < 6) continue; // malformed row
            try {
                BigDecimal nav = new BigDecimal(f[4].trim());
                LocalDate date = LocalDate.parse(f[5].trim(), AMFI_DATE);
                out.add(AmfiNavResult.builder()
                    .schemeCode(f[0].trim()).schemeName(f[3].trim()).nav(nav).asOf(date)
                    .category(category)
                    .schemeType(schemeType)
                    .amc(amc)
                    .categoryBucket(MfCategoryBucket.from(category))
                    .build());
            } catch (Exception ignored) {
                // not a data row (the column header line, or a malformed row) — skip
            }
        }
        return out;
    }

    /**
     * Category lines say "Schemes" and/or wrap the SEBI category in parentheses
     * ("Open Ended Schemes(Equity Scheme - Large Cap Fund)"). The other semicolon-free lines
     * are fund-house names, which conventionally end in "Mutual Fund".
     */
    private boolean isCategoryLine(String line) {
        String l = line.toLowerCase(Locale.ENGLISH);
        if (l.endsWith("mutual fund")) return false; // unambiguously an AMC
        return l.contains("scheme") || (line.indexOf('(') >= 0 && line.indexOf(')') > line.indexOf('('));
    }

    /** "Open Ended Schemes(Equity Scheme - Large Cap Fund)" -> "Equity Scheme - Large Cap Fund". */
    private String extractCategory(String line) {
        int open = line.indexOf('(');
        int close = line.lastIndexOf(')');
        if (open >= 0 && close > open) {
            String inner = line.substring(open + 1, close).trim();
            if (!inner.isEmpty()) return inner;
        }
        return line; // no parentheses — keep the raw text as the category
    }

    /** Reads the scheme type off the prefix before "Schemes(...)"; null when absent. */
    private String extractSchemeType(String line) {
        String l = line.toLowerCase(Locale.ENGLISH);
        if (l.startsWith("open ended") || l.startsWith("open-ended")) return "Open Ended";
        if (l.startsWith("close ended") || l.startsWith("closed ended")
            || l.startsWith("close-ended") || l.startsWith("closed-ended")) return "Close Ended";
        if (l.startsWith("interval")) return "Interval";
        return null;
    }

    /** Best-effort fuzzy match by scheme name — AMFI's exact naming rarely matches what a
     *  user typed/imported, so this does a normalized substring match on both sides and
     *  returns the closest-length match among candidates. */
    public AmfiNavResult findByName(String query) {
        if (query == null || query.trim().length() < 3) return null;
        String q = normalize(query);
        List<AmfiNavResult> data = cache.get();
        AmfiNavResult best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (AmfiNavResult r : data) {
            String name = normalize(r.getSchemeName());
            if (name.contains(q) || q.contains(name)) {
                int diff = Math.abs(name.length() - q.length());
                if (diff < bestDiff) { best = r; bestDiff = diff; }
            }
        }
        return best;
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
    }

    public int cachedSchemeCount() { return cache.get().size(); }
}
