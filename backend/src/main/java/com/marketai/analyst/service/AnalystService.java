package com.marketai.analyst.service;

import com.marketai.ai.client.GeminiClient;
import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.analyst.dto.AnalystAssessment.*;
import com.marketai.market.dto.QuoteDto;
import com.marketai.market.service.MarketDataService;
import com.marketai.news.entity.News;
import com.marketai.news.service.NewsService;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A financial-analyst-style verdict that blends four transparent factors rather
 * than technicals alone:
 *   Technical (30%)  – trend, RSI, MACD signal
 *   Momentum  (20%)  – where price sits in its 52-week range
 *   Valuation (20%)  – P/E bucket
 *   Sentiment (30%)  – keyword sentiment over recent stock-specific news
 * Composite → BUY / HOLD / SELL with a factor breakdown, the fundamentals it
 * used, and (if Gemini is configured) a short written outlook.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalystService {

    private final TechnicalIndicatorService technical;
    private final MarketDataService marketData;
    private final NewsService newsService;
    private final GeminiClient gemini;

    // Configurable per the "Financial Intelligence Engine" requirement — tune weighting
    // without a code change. Must sum to 100; defaults match the original hardcoded model.
    @org.springframework.beans.factory.annotation.Value("${app.recommendation.weights.technical:30}")
    private int weightTechnical;
    @org.springframework.beans.factory.annotation.Value("${app.recommendation.weights.momentum:20}")
    private int weightMomentum;
    @org.springframework.beans.factory.annotation.Value("${app.recommendation.weights.valuation:20}")
    private int weightValuation;
    @org.springframework.beans.factory.annotation.Value("${app.recommendation.weights.sentiment:30}")
    private int weightSentiment;

    private static final String[] POS = {"surge","jump","gain","gains","profit","beat","beats","record","high",
        "upgrade","buy","bullish","rally","rallies","growth","wins","win","order","approval","strong","soar","rise","rises","outperform","expansion","acquire"};
    private static final String[] NEG = {"fall","falls","drop","drops","loss","losses","decline","declines","downgrade",
        "sell","bearish","probe","fraud","ban","penalty","cut","cuts","weak","miss","misses","crash","slump","slumps","resign","default","lawsuit","fine","scam","plunge"};

    public AnalystAssessment assess(String symbol, String displayName) {
        String base = symbol.replace(".NS", "").replace(".BO", "");
        TechnicalAnalysisDto ta = technical.analyse(symbol);
        QuoteDto q = null;
        try { q = marketData.getQuote(symbol); } catch (Exception ignored) {}

        double price = ta.getPrice() != null ? ta.getPrice().doubleValue() : (q != null && q.getCurrentPrice() != null ? q.getCurrentPrice().doubleValue() : 0);

        // No usable price history → no honest composite is possible. Return an explicitly
        // unscored assessment rather than running the factor model over absent/limited inputs
        // and emitting a confident-looking BUY/HOLD/SELL from it.
        if ("INSUFFICIENT".equals(ta.getDataQuality())) {
            List<String> why = new ArrayList<>();
            why.add(String.format("Only %d day(s) of price history stored for %s — indicators (RSI, MACD, moving averages, ATR) cannot be computed.",
                ta.getBarsAvailable() != null ? ta.getBarsAvailable() : 0, base));
            why.add("No rating is shown because it would not be based on verifiable data.");
            return AnalystAssessment.builder()
                .symbol(symbol).displayName(displayName != null ? displayName : base).price(round(price))
                .rating(null).conviction(null).compositeScore(0)
                .factorBreakdown(new ArrayList<>())
                .fundamentals(Fundamentals.builder()
                    .pe(q != null ? q.getPe() : null).marketCap(q != null ? q.getMarketCap() : null)
                    .weekHigh52(q != null ? q.getWeekHigh52() : null).weekLow52(q != null ? q.getWeekLow52() : null)
                    .sector(q != null ? q.getSector() : null).trend("UNKNOWN").build())
                .technicals(null).news(null)
                .positives(new ArrayList<>()).risks(why)
                .basis("Insufficient price history to compute technical indicators. Fetch history for this symbol, then re-run.")
                .dataQuality("INSUFFICIENT").barsAvailable(ta.getBarsAvailable())
                .build();
        }

        String trend = ta.getTrend() != null ? ta.getTrend() : "SIDEWAYS";
        double rsi = ta.getRsi() != null ? ta.getRsi().doubleValue() : 50;

        List<String> positives = new ArrayList<>();
        List<String> risks = new ArrayList<>();

        // ── Technical factor ─────────────────────────────────────
        int tech;
        switch (trend) {
            case "STRONG_UPTREND": tech = 80; break;
            case "UPTREND": tech = 45; break;
            case "DOWNTREND": tech = -45; break;
            case "STRONG_DOWNTREND": tech = -80; break;
            default: tech = 0;
        }
        if ("BUY".equals(ta.getSignal())) tech += 15;
        else if ("SELL".equals(ta.getSignal())) tech -= 15;
        if (rsi >= 70) { tech -= 10; risks.add("Overbought (RSI " + (int) rsi + ") — near-term pullback risk"); }
        else if (rsi <= 30) { tech += 10; positives.add("Oversold (RSI " + (int) rsi + ") — potential bounce"); }
        tech = clamp(tech);
        if (trend.contains("UPTREND")) positives.add("Price trend is " + trend.toLowerCase().replace('_', ' '));
        if (trend.contains("DOWNTREND")) risks.add("Price trend is " + trend.toLowerCase().replace('_', ' '));

        // ── Momentum factor (52-week position) ──────────────────
        int mom = 0; Double pct52 = null;
        if (q != null && q.getWeekHigh52() != null && q.getWeekLow52() != null) {
            double hi = q.getWeekHigh52().doubleValue(), lo = q.getWeekLow52().doubleValue();
            if (hi > lo && price > 0) {
                pct52 = (price - lo) / (hi - lo) * 100;
                mom = (int) Math.round((pct52 - 50) * 1.6); // 0%→-80, 100%→+80
                if (pct52 >= 80) positives.add(String.format("Near 52-week high (%.0f%% of range)", pct52));
                else if (pct52 <= 20) risks.add(String.format("Near 52-week low (%.0f%% of range)", pct52));
            }
        }
        mom = clamp(mom);

        // ── Valuation factor (P/E) ──────────────────────────────
        int val = 0;
        BigDecimal pe = q != null ? q.getPe() : null;
        if (pe != null && pe.doubleValue() > 0) {
            double p = pe.doubleValue();
            if (p < 15) { val = 55; positives.add(String.format("Attractive valuation (P/E %.1f)", p)); }
            else if (p < 25) val = 20;
            else if (p < 40) { val = -10; }
            else { val = -50; risks.add(String.format("Expensive (P/E %.1f)", p)); }
        }

        // ── Sentiment factor (news) ─────────────────────────────
        NewsPulse pulse = newsSentiment(base, displayName);
        int sent = pulse.getScore();
        if (sent >= 30) positives.add("Positive recent news flow");
        else if (sent <= -30) risks.add("Negative recent news flow");

        // ── Composite ───────────────────────────────────────────
        int composite = (int) Math.round(
            tech * (weightTechnical / 100.0) + mom * (weightMomentum / 100.0)
            + val * (weightValuation / 100.0) + sent * (weightSentiment / 100.0));
        composite = clamp(composite);
        String rating = composite >= 25 ? "BUY" : composite <= -25 ? "SELL" : "HOLD";
        String conviction = Math.abs(composite) >= 55 ? "HIGH" : Math.abs(composite) >= 25 ? "MEDIUM" : "LOW";

        List<Factor> factors = new ArrayList<>();
        factors.add(Factor.builder().name("Technical").score(tech).weightPct(weightTechnical).note(trend.replace('_', ' ').toLowerCase() + ", RSI " + (int) rsi + ", " + (ta.getSignal() != null ? ta.getSignal() : "HOLD")).build());
        factors.add(Factor.builder().name("Momentum").score(mom).weightPct(weightMomentum).note(pct52 != null ? String.format("%.0f%% of 52-week range", pct52) : "52-week data unavailable").build());
        factors.add(Factor.builder().name("Valuation").score(val).weightPct(weightValuation).note(pe != null && pe.doubleValue() > 0 ? "P/E " + String.format("%.1f", pe.doubleValue()) : "P/E unavailable").build());
        factors.add(Factor.builder().name("Sentiment").score(sent).weightPct(weightSentiment).note(pulse.getTotal() + " recent articles (" + pulse.getPositive() + "+ / " + pulse.getNegative() + "-)").build());

        Fundamentals fund = Fundamentals.builder()
            .pe(pe).marketCap(q != null ? q.getMarketCap() : null)
            .weekHigh52(q != null ? q.getWeekHigh52() : null).weekLow52(q != null ? q.getWeekLow52() : null)
            .pctOf52wRange(pct52 != null ? Math.round(pct52 * 10) / 10.0 : null)
            .sector(q != null ? q.getSector() : null).trend(trend).rsi(ta.getRsi())
            .build();

        TechnicalLevels tl = TechnicalLevels.builder()
            .sma20(ta.getSma20()).sma50(ta.getSma50()).sma200(ta.getSma200())
            .support(ta.getSupport()).resistance(ta.getResistance())
            .macd(ta.getMacd()).macdSignal(ta.getMacdSignal()).atr(ta.getAtr())
            .bollingerUpper(ta.getBollingerUpper()).bollingerLower(ta.getBollingerLower())
            .dayChangePercent(q != null ? q.getChangePercent() : null)
            .volume(q != null ? q.getVolume() : null)
            .build();

        // State the ACTUAL number of bars behind this, not a fixed "~200 days" claim — the
        // window is whatever happens to be stored for this symbol and is frequently less.
        int bars = ta.getBarsAvailable() != null ? ta.getBarsAvailable() : 0;
        String historyNote = "PARTIAL".equals(ta.getDataQuality())
            ? String.format("%d days of price history (under 200, so the 200-DMA long-term filter was unavailable)", bars)
            : String.format("%d days of price history", bars);
        String basis = String.format(
            "Composite %d = Technical %d×%d%% + Momentum %d×%d%% + Valuation %d×%d%% + Sentiment %d×%d%%. Grounded in %s, 52-week range, %s and %d recent news items. Not investment advice.",
            composite, tech, weightTechnical, mom, weightMomentum, val, weightValuation, sent, weightSentiment,
            historyNote,
            (pe != null && pe.doubleValue() > 0 ? "P/E" : "no P/E available"),
            pulse.getTotal());

        String narrative = aiNarrative(displayName, rating, factors, fund, pulse);

        return AnalystAssessment.builder()
            .symbol(symbol).displayName(displayName != null ? displayName : base).price(round(price))
            .rating(rating).conviction(conviction).compositeScore(composite).factorBreakdown(factors)
            .fundamentals(fund).technicals(tl).news(pulse).positives(dedup(positives)).risks(dedup(risks))
            .aiNarrative(narrative).basis(basis)
            .dataQuality(ta.getDataQuality()).barsAvailable(ta.getBarsAvailable())
            .build();
    }

    private NewsPulse newsSentiment(String base, String name) {
        List<Article> articles = new ArrayList<>();
        // 1) Live Google News for the company — real, recent headlines with links
        articles.addAll(fetchGoogleNews(name != null && name.length() > 2 ? name : base));
        // 2) Supplement with any stored per-symbol news
        try {
            for (News n : newsService.getNewsBySymbol(base)) {
                if (articles.size() >= 8 || n.getTitle() == null) break;
                articles.add(Article.builder().title(n.getTitle()).url(n.getUrl())
                    .source(n.getSource() != null ? n.getSource() : "News")
                    .publishedAt(n.getPublishedAt() != null ? n.getPublishedAt().toString() : null).build());
            }
        } catch (Exception ignored) {}

        int pos = 0, neg = 0;
        List<String> heads = new ArrayList<>();
        for (Article a : articles) {
            String hay = (a.getTitle() == null ? "" : a.getTitle()).toLowerCase();
            int p = count(hay, POS), ng = count(hay, NEG);
            if (p > ng) pos++; else if (ng > p) neg++;
            if (heads.size() < 5 && a.getTitle() != null) heads.add(a.getTitle());
        }
        int total = pos + neg;
        int score = total > 0 ? (int) Math.round((pos - neg) * 100.0 / total) : 0;
        return NewsPulse.builder().score(score).positive(pos).negative(neg).total(articles.size())
            .headlines(heads).articles(articles.subList(0, Math.min(articles.size(), 6))).build();
    }

    private List<Article> fetchGoogleNews(String query) {
        List<Article> out = new ArrayList<>();
        try {
            String url = "https://news.google.com/rss/search?q=" +
                java.net.URLEncoder.encode(query + " stock NSE", "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en";
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(7000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line; while ((line = r.readLine()) != null) sb.append(line);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<item>(.*?)</item>", java.util.regex.Pattern.DOTALL).matcher(sb.toString());
            while (m.find() && out.size() < 6) {
                String item = m.group(1);
                String title = tag(item, "title"), link = tag(item, "link"), date = tag(item, "pubDate"), src = tag(item, "source");
                if (title != null) out.add(Article.builder().title(clean(title)).url(link)
                    .source(src != null ? clean(src) : "Google News").publishedAt(shortDate(date)).build());
            }
        } catch (Exception e) { log.debug("news fetch failed for {}: {}", query, e.getMessage()); }
        return out;
    }
    private String tag(String xml, String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<" + t + "[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + t + ">", java.util.regex.Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }
    private String clean(String s) { return s.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&nbsp;", " ").trim(); }
    private String shortDate(String d) { if (d == null) return null; return d.length() >= 16 ? d.substring(5, 16) : d; }

    private String aiNarrative(String name, String rating, List<Factor> factors, Fundamentals f, NewsPulse pulse) {
        try {
            StringBuilder ctx = new StringBuilder();
            ctx.append("Stock: ").append(name).append("\nModel rating: ").append(rating).append("\nFactors: ");
            for (Factor x : factors) ctx.append(x.getName()).append("=").append(x.getScore()).append(" (").append(x.getNote()).append("); ");
            ctx.append("\nP/E: ").append(f.getPe()).append(", sector: ").append(f.getSector()).append(", trend: ").append(f.getTrend());
            if (pulse.getHeadlines() != null && !pulse.getHeadlines().isEmpty())
                ctx.append("\nRecent headlines: ").append(String.join(" | ", pulse.getHeadlines()));
            String sys = "You are a sell-side equity analyst. In 2-3 sentences give a balanced outlook for this Indian stock using ONLY the data provided. Mention the key driver and the main risk. Do not invent numbers. End with 'Not investment advice.'";
            String out = gemini.generateContent(sys, ctx.toString());
            return (out != null && !out.trim().isEmpty()) ? out.trim() : null;
        } catch (Exception e) {
            return null; // Gemini not configured / unavailable — deterministic model still stands
        }
    }

    private int count(String hay, String[] words) { int c = 0; for (String w : words) if (hay.contains(w)) c++; return c; }
    private int clamp(int v) { return Math.max(-100, Math.min(100, v)); }
    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private List<String> dedup(List<String> in) { List<String> o = new ArrayList<>(); for (String s : in) if (!o.contains(s)) o.add(s); return o; }
}
