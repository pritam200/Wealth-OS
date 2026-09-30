package com.marketai.research.service;

import com.marketai.market.client.NseClient;
import com.marketai.news.service.CompanyNewsService;
import com.marketai.news.service.NewsSentimentAnalyzer;
import com.marketai.research.model.Evidence;
import com.marketai.research.model.SourceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Collects dated, sourced items for research before any model is called: NSE filings,
 * corporate actions, board meetings and results filings (primary sources), then company or
 * market news. Each source's outcome is recorded, so "NSE unreachable" is never mistaken for
 * "no filings".
 *
 * <p>Source ranking: exchange/company filing (PRIMARY) &gt; company release (OFFICIAL) &gt;
 * established financial press (RELIABLE) &gt; other news (NEWS) &gt; social/aggregator (UNVERIFIED).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvidenceRetriever {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int FILINGS = 8, ACTIONS = 5, MEETINGS = 4, RESULTS = 2, NEWS = 10;
    /** Filings older than this are not "current information". Board meetings may be upcoming. */
    static final int FILING_WINDOW_DAYS = 120;

    private final NseClient nse;
    private final CompanyNewsService news;

    public record Retrieved(List<Evidence> evidence, List<SourceStatus> sources, NewsSentimentAnalyzer.Result sentiment) {}

    public Retrieved forCompany(String base, String name) {
        LocalDateTime now = LocalDateTime.now(IST);
        List<Evidence> out = new ArrayList<>();
        List<SourceStatus> status = new ArrayList<>();

        nse("NSE announcements", () -> nse.announcements(base, FILINGS), now, out, status, true);
        nse("NSE corporate actions", () -> nse.corporateActions(base, ACTIONS), now, out, status, false);
        nse("NSE board meetings", () -> nse.boardMeetings(base, MEETINGS), now, out, status, false);
        nse("NSE results filings", () -> nse.resultsFilings(base, RESULTS), now, out, status, false);

        CompanyNewsService.Fetch f = news.forCompany(base, name);
        Set<String> terms = new LinkedHashSet<>();
        terms.add(base);
        if (name != null) {
            terms.add(name);
            String first = name.split("\\s+")[0];
            if (first.length() >= 4) terms.add(first);
        }
        NewsSentimentAnalyzer.Result r = NewsSentimentAnalyzer.analyse(f.headlines(), terms, now);
        addNews(r, now, out, status, f);
        return new Retrieved(out, status, r);
    }

    public Retrieved forMarket() {
        LocalDateTime now = LocalDateTime.now(IST);
        List<Evidence> out = new ArrayList<>();
        List<SourceStatus> status = new ArrayList<>();
        CompanyNewsService.Fetch f = news.forMarket();
        NewsSentimentAnalyzer.Result r = NewsSentimentAnalyzer.analyse(f.headlines(),
                List.of("Nifty", "Sensex", "RBI", "market", "markets", "rupee", "crude", "inflation", "FII", "FIIs", "economy", "GDP", "Fed", "stocks"), now);
        addNews(r, now, out, status, f);
        return new Retrieved(out, status, r);
    }

    /** News for a fund: its fund house and scheme name. */
    public Retrieved forFund(String schemeName, String amc) {
        LocalDateTime now = LocalDateTime.now(IST);
        List<Evidence> out = new ArrayList<>();
        List<SourceStatus> status = new ArrayList<>();
        CompanyNewsService.Fetch f = news.forCompany(amc != null ? amc : schemeName, (amc != null ? amc : schemeName) + " mutual fund");
        List<String> terms = new ArrayList<>();
        if (amc != null) terms.add(amc);
        terms.add(schemeName);
        NewsSentimentAnalyzer.Result r = NewsSentimentAnalyzer.analyse(f.headlines(), terms, now);
        addNews(r, now, out, status, f);
        return new Retrieved(out, status, r);
    }

    private void addNews(NewsSentimentAnalyzer.Result r, LocalDateTime now, List<Evidence> out, List<SourceStatus> status,
                         CompanyNewsService.Fetch f) {
        int added = 0;
        for (NewsSentimentAnalyzer.Classified c : r.counted()) {
            if (added >= NEWS) break;
            String tier = tierOf(c.headline().source(), c.headline().url());
            String relevance = c.ageDays() <= 3 ? "HIGH" : c.ageDays() <= 7 ? "MEDIUM" : "LOW";
            out.add(new Evidence(null, "NEWS", tier, c.headline().source(), c.headline().title(), c.headline().publishedAt(),
                    c.headline().url(), now, relevance,
                    "Keyword sentiment " + c.sentiment().toLowerCase(Locale.ROOT) + (c.event() != null && !"GENERAL".equals(c.event()) ? "; event " + c.event() : "")));
            added++;
        }
        for (String s : f.sourcesOk()) status.add(SourceStatus.ok(s, added));
        for (String s : f.sourcesFailed()) status.add(SourceStatus.unavailable(s, "Could not be fetched"));
        if (r.counted().isEmpty() && !f.headlines().isEmpty()) {
            status.add(new SourceStatus("News relevance filter", "OK", 0,
                    f.headlines().size() + " headline(s) fetched; none dated within " + NewsSentimentAnalyzer.WINDOW_DAYS + " days and naming the subject"));
        }
    }

    private void nse(String label, java.util.function.Supplier<List<NseClient.Item>> call, LocalDateTime now,
                     List<Evidence> out, List<SourceStatus> status, boolean windowed) {
        try {
            List<NseClient.Item> items = call.get();
            int n = 0;
            for (NseClient.Item i : items) {
                if (windowed && i.date() != null && ChronoUnit.DAYS.between(i.date(), now) > FILING_WINDOW_DAYS) continue;
                long age = i.date() == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(i.date(), now);
                String relevance = age < 0 ? "HIGH" /* upcoming */ : age <= 14 ? "HIGH" : age <= 60 ? "MEDIUM" : "LOW";
                out.add(new Evidence(null, i.kind(), "PRIMARY", "NSE", i.title(), i.date(), i.url(), now, relevance, trim(i.detail(), 400)));
                n++;
            }
            status.add(SourceStatus.ok(label, n));
        } catch (Exception e) {
            log.debug("{} failed: {}", label, e.getMessage());
            status.add(SourceStatus.unavailable(label, e.getMessage()));
        }
    }

    static final Set<String> RELIABLE = Set.of("reuters.com", "bloomberg.com", "economictimes.indiatimes.com", "livemint.com",
            "business-standard.com", "moneycontrol.com", "thehindubusinessline.com", "financialexpress.com", "cnbctv18.com",
            "ndtvprofit.com", "businesstoday.in", "ft.com", "wsj.com", "rbi.org.in", "sebi.gov.in", "pib.gov.in");
    static final Set<String> RELIABLE_NAMES = Set.of("reuters", "bloomberg", "the economic times", "economic times", "mint", "livemint",
            "business standard", "moneycontrol", "the hindu businessline", "hindu businessline", "financial express", "cnbc tv18",
            "cnbctv18", "ndtv profit", "business today", "financial times", "the wall street journal");
    static final Set<String> PRIMARY = Set.of("nseindia.com", "bseindia.com", "nsearchives.nseindia.com", "sebi.gov.in", "rbi.org.in");
    static final Set<String> UNVERIFIED = Set.of("twitter.com", "x.com", "reddit.com", "facebook.com", "t.me", "youtube.com",
            "instagram.com", "quora.com", "medium.com");

    /** Tier from the URL's host when it is the publisher's, else from the publisher name. */
    public static String tierOf(String source, String url) {
        String host = host(url);
        if (host != null) {
            if (inDomain(host, PRIMARY)) return "PRIMARY";
            if (inDomain(host, UNVERIFIED)) return "UNVERIFIED";
            if (inDomain(host, RELIABLE)) return "RELIABLE";
        }
        String s = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        // Search grounding names a page by its domain ("reuters.com") and links through a redirect.
        if (s.matches("[a-z0-9.-]+\\.[a-z]{2,}")) {
            String d = s.replaceFirst("^www\\.", "");
            if (inDomain(d, PRIMARY)) return "PRIMARY";
            if (inDomain(d, UNVERIFIED)) return "UNVERIFIED";
            if (inDomain(d, RELIABLE)) return "RELIABLE";
            return "NEWS";
        }
        if (s.equals("nse") || s.equals("bse")) return "PRIMARY";
        if (RELIABLE_NAMES.contains(s)) return "RELIABLE";
        return s.isEmpty() ? "UNVERIFIED" : "NEWS";
    }

    /** The host is one of the domains or a subdomain of one — "upstox.com" is not "x.com". */
    static boolean inDomain(String host, Set<String> domains) {
        return domains.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
    }

    static String host(String url) {
        if (url == null) return null;
        try {
            String h = URI.create(url.trim()).getHost();
            if (h == null || h.endsWith("news.google.com") || h.endsWith("vertexaisearch.cloud.google.com")) return null;
            return h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return null;
        }
    }

    static String trim(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
