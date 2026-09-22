package com.marketai.analyst.service;

import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmProviderRouter;
import com.marketai.ai.llm.LlmUnavailableException;
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
import com.marketai.common.quality.DataQuality;

/**
 * A financial-analyst-style verdict that blends four transparent factors rather
 * than technicals alone:
 *   Technical (30%)  – trend, RSI, MACD signal
 *   Momentum  (20%)  – where price sits in its 52-week range
 *   Valuation (20%)  – P/E bucket
 *   Sentiment (30%)  – keyword sentiment over recent stock-specific news
 * Composite → BUY / HOLD / SELL with a factor breakdown, the fundamentals it used, and
 * (when a model is available — local Ollama by default, Gemini if configured) an AI second
 * opinion carried in the `ai*` fields.
 *
 * The AI view is strictly advisory: it is produced AFTER the composite is final, is never an
 * input to it, and a disagreement changes nothing. If no model is reachable, every `ai*` field
 * is null and the verdict is identical.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalystService {

    private final TechnicalIndicatorService technical;
    private final MarketDataService marketData;
    private final NewsService newsService;
    // Vendor-neutral: Ollama locally by default, Gemini only when configured. Was GeminiClient
    // directly, which made app.llm.provider=none have no effect on this path.
    private final LlmProviderRouter llm;
    private final LlmJsonParser llmJson;

    // Configurable per the "Financial Intelligence Engine" requirement — tune weighting
    // without a code change. Must sum to 100; defaults match the original hardcoded model.
    @org.springframework.beans.factory.annotation.Value("${app.recommendation.weights.technical:25}")
    private int weightTechnical;
    @org.springframework.beans.factory.annotation.Value("${app.recommendation.weights.momentum:15}")
    private int weightMomentum;
    @org.springframework.beans.factory.annotation.Value("${app.recommendation.weights.valuation:40}")
    private int weightValuation;
    @org.springframework.beans.factory.annotation.Value("${app.recommendation.weights.sentiment:20}")
    private int weightSentiment;

    /**
     * The weights must sum to 100 or every composite is silently mis-scaled — weights summing
     * to 90 shrink every score by a tenth, moving stocks across the BUY/HOLD/SELL thresholds
     * with nothing reporting it. That requirement was previously only a comment in
     * application.yml, which is documentation, not enforcement.
     *
     * Failing at startup is deliberate: a mis-weighted recommendation engine that runs is worse
     * than one that refuses to, because its output is indistinguishable from a correct one.
     */
    @jakarta.annotation.PostConstruct
    void validateWeights() {
        int sum = weightTechnical + weightMomentum + weightValuation + weightSentiment;
        if (sum != 100) {
            throw new IllegalStateException(String.format(
                "Recommendation weights must sum to 100 but sum to %d "
                    + "(technical=%d, momentum=%d, valuation=%d, sentiment=%d). "
                    + "Every composite score would be scaled by %.2f.",
                sum, weightTechnical, weightMomentum, weightValuation, weightSentiment,
                sum / 100.0));
        }
        if (weightTechnical < 0 || weightMomentum < 0 || weightValuation < 0 || weightSentiment < 0) {
            throw new IllegalStateException("Recommendation weights cannot be negative");
        }
    }

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
        if (!DataQuality.of(ta.getDataQuality()).isUsable()) {
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
                .dataQuality(DataQuality.INSUFFICIENT.wire()).barsAvailable(ta.getBarsAvailable())
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
        String historyNote = DataQuality.of(ta.getDataQuality()).needsCaveat()
            ? String.format("%d days of price history (under 200, so the 200-DMA long-term filter was unavailable)", bars)
            : String.format("%d days of price history", bars);
        String basis = String.format(
            "Composite %d = Technical %d×%d%% + Momentum %d×%d%% + Valuation %d×%d%% + Sentiment %d×%d%%. Grounded in %s, 52-week range, %s and %d recent news items. Not investment advice.",
            composite, tech, weightTechnical, mom, weightMomentum, val, weightValuation, sent, weightSentiment,
            historyNote,
            (pe != null && pe.doubleValue() > 0 ? "P/E" : "no P/E available"),
            pulse.getTotal());

        // Asked for only after `rating`/`composite` above are final — the AI cannot influence
        // them, it can only comment on them.
        AiView ai = aiView(displayName, rating, factors, fund, pulse);

        return AnalystAssessment.builder()
            .symbol(symbol).displayName(displayName != null ? displayName : base).price(round(price))
            .rating(rating).conviction(conviction).compositeScore(composite).factorBreakdown(factors)
            .fundamentals(fund).technicals(tl).news(pulse).positives(dedup(positives)).risks(dedup(risks))
            .aiNarrative(ai == null ? null : ai.outlook())
            .aiRating(ai == null ? null : ai.rating())
            .aiKeyDriver(ai == null ? null : ai.keyDriver())
            .aiMainRisk(ai == null ? null : ai.mainRisk())
            // Agreement is computed here in Java, never asserted by the model itself.
            .aiAgrees(ai == null || ai.rating() == null ? null : ai.rating().equals(rating))
            .aiProvider(ai == null ? null : ai.provider())
            .basis(basis)
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
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
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

    /** Advisory-only companion to the deterministic assessment. All fields may be null. */
    record AiView(String rating, String keyDriver, String mainRisk, String outlook, String provider) {}

    private static final java.util.Set<String> ALLOWED_AI_RATINGS =
        java.util.Set.of("BUY", "HOLD", "SELL", "WATCH");

    /**
     * The AI second opinion: the model's own call plus a short outlook, requested as JSON so
     * the verdict can be displayed BESIDE the deterministic rating instead of blended into it.
     *
     * Every failure mode degrades to "no AI view" (null) and leaves the composite untouched:
     * no model configured, model unreachable, non-JSON output, or a rating outside the
     * whitelist. An unrecognised verdict is dropped rather than shown as a rating.
     */
    private AiView aiView(String name, String rating, List<Factor> factors, Fundamentals f, NewsPulse pulse) {
        try {
            StringBuilder ctx = new StringBuilder();
            ctx.append("Stock: ").append(name).append("\nModel rating: ").append(rating).append("\nFactors: ");
            for (Factor x : factors) ctx.append(x.getName()).append("=").append(x.getScore()).append(" (").append(x.getNote()).append("); ");
            ctx.append("\nP/E: ").append(f.getPe()).append(", sector: ").append(f.getSector()).append(", trend: ").append(f.getTrend());
            if (pulse.getHeadlines() != null && !pulse.getHeadlines().isEmpty())
                ctx.append("\nRecent headlines: ").append(String.join(" | ", pulse.getHeadlines()));

            String sys = "You are a sell-side equity analyst reviewing an Indian (NSE/BSE) stock. "
                + "Use ONLY the data provided — never invent numbers, prices or events. "
                + "Reply with a JSON object and nothing else: "
                + "{\"rating\": one of BUY|HOLD|SELL|WATCH — your own independent call, "
                + "\"keyDriver\": one short sentence on what matters most here, "
                + "\"mainRisk\": one short sentence on the biggest risk, "
                + "\"outlook\": 2-3 sentences of balanced outlook ending with 'Not investment advice.'} "
                + "Use WATCH when the data provided is too thin to take a side.";

            LlmCompletion out = llm.complete(sys, ctx.toString());
            var json = llmJson.parse(out.getText()).orElse(null);
            if (json == null) return null;

            String aiRating = llmJson.str(json, "rating");
            if (aiRating != null) {
                aiRating = aiRating.trim().toUpperCase();
                if (!ALLOWED_AI_RATINGS.contains(aiRating)) aiRating = null;
            }

            AiView view = new AiView(aiRating, llmJson.str(json, "keyDriver"), llmJson.str(json, "mainRisk"),
                llmJson.str(json, "outlook"), out.getProvider() + ":" + out.getModel());
            // Nothing usable came back — treat as no AI view rather than an empty panel.
            if (view.rating() == null && view.outlook() == null) return null;
            return view;
        } catch (LlmUnavailableException e) {
            return null; // no model configured/reachable — the deterministic model still stands
        } catch (Exception e) {
            log.debug("AI view unavailable for {}: {}", name, e.getMessage());
            return null;
        }
    }

    private int count(String hay, String[] words) { int c = 0; for (String w : words) if (hay.contains(w)) c++; return c; }
    private int clamp(int v) { return Math.max(-100, Math.min(100, v)); }
    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private List<String> dedup(List<String> in) { List<String> o = new ArrayList<>(); for (String s : in) if (!o.contains(s)) o.add(s); return o; }
}
