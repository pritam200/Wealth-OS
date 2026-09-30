package com.marketai.market.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Primary-source company information from the NSE website's public JSON endpoints: exchange
 * announcements (filings), corporate actions, board-meeting calendar and financial-results
 * filings. Read-only; every call is best-effort and reports failure as an exception the caller
 * records — never as "no filings".
 */
@Component
@Slf4j
public class NseClient {

    static final String BASE = "https://www.nseindia.com/api/";
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final DateTimeFormatter ANN = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter FILED = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH);

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String base;

    @Autowired
    public NseClient(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build(), BASE);
    }

    NseClient(ObjectMapper mapper, HttpClient http, String base) {
        this.mapper = mapper;
        this.http = http;
        this.base = base;
    }

    /** A dated item from NSE with a link to the source document when there is one. */
    public record Item(String kind, String title, String detail, LocalDateTime date, String url) {}

    /** Exchange announcements, newest first. */
    public List<Item> announcements(String symbol, int max) {
        List<Item> out = new ArrayList<>();
        for (JsonNode n : get("corporate-announcements?index=equities&symbol=" + enc(symbol))) {
            String desc = text(n, "desc"), body = text(n, "attchmntText");
            out.add(new Item("FILING", desc != null ? desc : "Announcement", body, parse(text(n, "an_dt"), ANN), text(n, "attchmntFile")));
        }
        return newest(out, max);
    }

    /** Dividends, splits, bonuses, rights — by ex-date, newest first. */
    public List<Item> corporateActions(String symbol, int max) {
        List<Item> out = new ArrayList<>();
        for (JsonNode n : get("corporates-corporateActions?index=equities&symbol=" + enc(symbol))) {
            LocalDate ex = parseDay(text(n, "exDate"));
            out.add(new Item("CORPORATE_ACTION", text(n, "subject"), "Ex-date " + text(n, "exDate") + ", record date " + text(n, "recDate"),
                    ex != null ? ex.atStartOfDay() : null, null));
        }
        return newest(out, max);
    }

    /** Board meetings (results dates, fund raising …), newest first — includes upcoming ones. */
    public List<Item> boardMeetings(String symbol, int max) {
        List<Item> out = new ArrayList<>();
        for (JsonNode n : get("event-calendar?index=equities&symbol=" + enc(symbol))) {
            LocalDate d = parseDay(text(n, "date"));
            out.add(new Item("EVENT", "Board meeting: " + text(n, "purpose"), text(n, "bm_desc"), d != null ? d.atStartOfDay() : null, null));
        }
        return newest(out, max);
    }

    /** Quarterly results filings: which period was reported and when it was filed. */
    public List<Item> resultsFilings(String symbol, int max) {
        List<Item> out = new ArrayList<>();
        for (JsonNode n : get("corporates-financial-results?index=equities&symbol=" + enc(symbol) + "&period=Quarterly")) {
            String period = text(n, "fromDate") + " to " + text(n, "toDate");
            String kind = text(n, "consolidated") + ", " + text(n, "audited");
            out.add(new Item("RESULTS_FILING", "Quarterly results filed for " + period, kind + " (" + text(n, "financialYear") + ")",
                    parse(text(n, "filingDate"), FILED), null));
        }
        return newest(out, max);
    }

    private JsonNode get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("Referer", "https://www.nseindia.com/")
                    .GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IllegalStateException("NSE HTTP " + r.statusCode());
            JsonNode root = mapper.readTree(r.body());
            JsonNode arr = root.isArray() ? root : root.path("data");
            if (!arr.isArray()) throw new IllegalStateException("NSE returned an unexpected shape");
            return arr;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("NSE unreachable: " + e.getClass().getSimpleName());
        }
    }

    private static List<Item> newest(List<Item> items, int max) {
        return items.stream()
                .sorted(Comparator.comparing(Item::date, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(max).toList();
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() || "-".equals(s) ? null : s;
    }

    static LocalDateTime parse(String s, DateTimeFormatter f) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s, f);
        } catch (Exception e) {
            return null;
        }
    }

    static LocalDate parseDay(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s, DAY);
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
