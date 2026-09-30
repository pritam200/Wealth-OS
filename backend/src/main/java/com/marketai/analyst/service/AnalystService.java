package com.marketai.analyst.service;

import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.analyst.dto.AnalystAssessment.*;
import com.marketai.market.dto.QuoteDto;
import com.marketai.market.service.MarketDataService;
import com.marketai.news.service.CompanyNewsService;
import com.marketai.news.service.NewsSentimentAnalyzer;
import com.marketai.research.model.ResearchResult;
import com.marketai.research.service.ResearchCache;
import com.marketai.signal.dto.SignalPayload;
import com.marketai.signal.service.SignalEngine;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import com.marketai.common.quality.DataQuality;

/**
 * Assembles one stock's analysis from the canonical engines, keeping each kind of evidence
 * separate:
 *   TECHNICAL_SIGNAL — SignalEngine's call, which is the only input to the rating, and only
 *                      when it has a demonstrated edge on this instrument's history
 *   MARKET_TREND     — TrendModel label and evidence (descriptive)
 *   MOMENTUM         — position in the 52-week range, from stored history (descriptive)
 *   VALUATION        — P/E, P/B, ROE, D/E … with period, source and fetch time (descriptive;
 *                      there is no sector-relative data to score them against)
 *   NEWS_SENTIMENT   — dated, de-duplicated, company-specific headlines (descriptive)
 *
 * The previous version blended fixed-bucket scores for all four into one number with
 * configurable weights and called BUY/SELL at ±25. None of those buckets, weights or cut-offs
 * had been tested against outcomes, and valuation and sentiment cannot be backtested at all
 * with the data available, so the blend was removed rather than re-tuned.
 *
 * The AI view is advisory only: produced after the rating, never an input to it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalystService {

    private final TechnicalIndicatorService technical;
    private final MarketDataService marketData;
    private final CompanyNewsService companyNews;
    private final ResearchCache research;
    private final SignalEngine signalEngine;

    public static final String NO_ACTIONABLE_SIGNAL = "NO_ACTIONABLE_SIGNAL";

    public AnalystAssessment assess(String symbol, String displayName) {
        String base = symbol.replace(".NS", "").replace(".BO", "");
        String name = displayName != null ? displayName : base;
        TechnicalAnalysisDto ta = technical.analyse(symbol);
        QuoteDto q = null;
        try { q = marketData.getQuote(symbol); } catch (Exception e) { log.debug("No quote for {}: {}", symbol, e.getMessage()); }

        if (!DataQuality.of(ta.getDataQuality()).isUsable()) {
            List<String> why = new ArrayList<>();
            why.add(String.format("Only %d valid daily bar(s) stored for %s — indicators cannot be computed.",
                    ta.getBarsAvailable() != null ? ta.getBarsAvailable() : 0, base));
            why.add("No rating is shown because it would not be based on verifiable data.");
            return AnalystAssessment.builder()
                    .symbol(symbol).displayName(name).price(ta.getPrice() != null ? ta.getPrice().doubleValue() : 0)
                    .rating("INSUFFICIENT_DATA").factorBreakdown(new ArrayList<>())
                    .fundamentalFacts(facts(q, ta)).positives(new ArrayList<>()).risks(why)
                    .basis("Insufficient price history to compute technical indicators.")
                    .dataQuality(DataQuality.INSUFFICIENT.wire()).barsAvailable(ta.getBarsAvailable())
                    .seriesStatus(ta.getSeriesStatus()).priceDate(ta.getLastBarDate()).priceSource(ta.getSource())
                    .build();
        }

        SignalPayload sig = signalEngine.analyse(symbol);
        List<String> positives = new ArrayList<>(), risks = new ArrayList<>();
        List<Factor> factors = new ArrayList<>();

        /* TECHNICAL_SIGNAL — the only rating input */
        String rating;
        String conviction = null;
        Integer hitRate = null;
        SignalPayload.Validation v = sig.getValidation();
        switch (sig.getSignal()) {
            case BUY, SELL -> {
                rating = sig.getSignal().name();
                boolean buy = sig.getSignal() == SignalPayload.Type.BUY;
                double rate = buy ? v.getBuyHitRate() : v.getSellHitRate();
                double edge = (buy ? v.getBuyHitRateCiLow() : v.getSellHitRateCiLow()) - (buy ? v.getBaseUpRate() : 1 - v.getBaseUpRate());
                conviction = edge >= 0.10 ? "HIGH" : edge >= 0.05 ? "MEDIUM" : "LOW";
                hitRate = (int) Math.round(rate * 100);
            }
            case STALE_DATA -> rating = "STALE_DATA";
            case INSUFFICIENT_DATA -> rating = "INSUFFICIENT_DATA";
            default -> rating = NO_ACTIONABLE_SIGNAL;
        }
        factors.add(Factor.builder().name("Technical signal").category("TECHNICAL_SIGNAL")
                .reading(sig.getSignal().name() + (sig.getRuleOutput() != sig.getSignal() ? " (rule reads " + sig.getRuleOutput() + ")" : ""))
                .note(v != null ? v.getSummary() : String.join(" ", sig.getRationale()))
                .contributesToRating(true).weightPct(100).build());

        /* MARKET_TREND */
        String trend = ta.getTrend();
        factors.add(Factor.builder().name("Trend").category("MARKET_TREND").reading(trend)
                .note(ta.getTrendAssessment() != null ? String.format("%d bullish / %d bearish of %d votes. %s",
                        ta.getTrendAssessment().getBullishVotes(), ta.getTrendAssessment().getBearishVotes(),
                        ta.getTrendAssessment().getVotesAvailable(), TrendNote.SHORT) : null)
                .build());
        if (trend != null && trend.contains("UPTREND")) positives.add("Price trend is " + trend.toLowerCase().replace('_', ' '));
        if (trend != null && trend.contains("DOWNTREND")) risks.add("Price trend is " + trend.toLowerCase().replace('_', ' '));
        if (ta.getRsi() != null) {
            double rsi = ta.getRsi().doubleValue();
            if (rsi >= 70) risks.add("RSI " + (int) rsi + " — overbought by the conventional 70 line");
            else if (rsi <= 30) positives.add("RSI " + (int) rsi + " — oversold by the conventional 30 line");
        }

        /* MOMENTUM — 52-week position from stored history */
        BigDecimal pos52 = ta.getRangePosition52wPct();
        factors.add(Factor.builder().name("52-week position").category("MOMENTUM")
                .reading(pos52 != null ? pos52.setScale(0, RoundingMode.HALF_UP) + "% of range" : "unavailable")
                .note(pos52 != null ? String.format("Close %s within %s–%s (last %d sessions).", ta.getPrice(), ta.getLow52w(), ta.getHigh52w(), ta.getRange52wSessions()) : null)
                .build());
        if (pos52 != null && pos52.doubleValue() >= 90) positives.add(String.format("Within the top tenth of its %d-session range", ta.getRange52wSessions()));
        if (pos52 != null && pos52.doubleValue() <= 10) risks.add(String.format("Within the bottom tenth of its %d-session range", ta.getRange52wSessions()));

        /* VALUATION — facts only */
        List<FundamentalFact> facts = facts(q, ta);
        FundamentalFact pe = facts.stream().filter(f -> f.getName().equals("P/E")).findFirst().orElse(null);
        factors.add(Factor.builder().name("Valuation").category("VALUATION")
                .reading(pe != null && pe.getValue() != null ? "P/E " + pe.getValue().setScale(1, RoundingMode.HALF_UP) : "P/E unavailable")
                .note("Shown as facts: no sector or history comparison is available to judge cheap or expensive.")
                .build());

        /* NEWS_SENTIMENT — separate */
        NewsPulse pulse = newsSentiment(base, name, q);
        factors.add(Factor.builder().name("News sentiment").category("NEWS_SENTIMENT")
                .score(pulse.getScore())
                .reading("OK".equals(pulse.getStatus()) ? pulse.getLabel() + " (" + pulse.getConfidence() + " confidence)" : "Insufficient recent company news")
                .note(String.format("%d headline(s) from the last %d days (+%d / −%d); %d excluded as old, undated, duplicate or unrelated.",
                        pulse.getTotal(), pulse.getWindowDays(), pulse.getPositive(), pulse.getNegative(), pulse.getExcluded()))
                .build());

        for (com.marketai.market.quality.DataIssue i : ta.getDataIssues()) if (i.isWarning()) risks.add("Data: " + i.detail());

        Fundamentals fund = Fundamentals.builder()
                .pe(pe != null ? pe.getValue() : null).marketCap(q != null ? q.getMarketCap() : null)
                .weekHigh52(ta.getHigh52w()).weekLow52(ta.getLow52w())
                .pctOf52wRange(pos52 != null ? pos52.doubleValue() : null)
                .sector(q != null ? q.getSector() : null).trend(trend).rsi(ta.getRsi())
                .build();
        TechnicalLevels tl = TechnicalLevels.builder()
                .sma20(ta.getSma20()).sma50(ta.getSma50()).sma200(ta.getSma200())
                .support(ta.getSupport()).resistance(ta.getResistance())
                .macd(ta.getMacd()).macdSignal(ta.getMacdSignal()).atr(ta.getAtr())
                .bollingerUpper(ta.getBollingerUpper()).bollingerLower(ta.getBollingerLower())
                .dayChangePercent(q != null ? q.getChangePercent() : null)
                .volume(ta.getVolume() != null ? ta.getVolume().getLatestVolume() : null)
                .build();

        String basis = String.format("Rating from the signal rule only, and only when its call has beaten the base rate on this stock's "
                        + "history (%s). Trend, 52-week position, valuation and news are shown as evidence and do not move the rating. "
                        + "Computed from %d daily bars to %s (%s). Not investment advice.",
                v != null ? v.getSummary() : "no validation possible", ta.getBarsAvailable(), ta.getLastBarDate(), ta.getSource());

        ResearchResult rr = research.currentForSymbol("STOCK", MarketDataService.canonicalSymbol(symbol).replace(".NS", "").replace(".BO", ""),
                ta.getLastBarDate()).orElse(null);

        return AnalystAssessment.builder()
                .symbol(symbol).displayName(name).price(ta.getPrice().doubleValue())
                .rating(rating).conviction(conviction)
                .compositeScore(hitRate != null ? Math.max(-100, Math.min(100, sig.getConfidence() == null ? 0 : (sig.getSignal() == SignalPayload.Type.SELL ? -sig.getConfidence() : sig.getConfidence()))) : null)
                .confidenceScore(hitRate)
                .factorBreakdown(factors)
                .ruleOutput(sig.getRuleOutput() != null ? sig.getRuleOutput().name() : null)
                .signalValidation(v)
                .trendAssessment(ta.getTrendAssessment())
                .fundamentalFacts(facts)
                .fundamentals(fund).technicals(tl).news(pulse).positives(dedup(positives)).risks(dedup(risks))
                .researchActionability(rr == null || rr.getFinalView() == null ? null : rr.getFinalView().actionability())
                .researchReason(rr == null || rr.getFinalView() == null ? null : rr.getFinalView().reason())
                .researchLean(rr == null || rr.getFinalView() == null ? null : rr.getFinalView().researchLean())
                .researchAt(rr == null ? null : rr.getResearchTimestamp())
                .researchModel(rr == null ? null : rr.getProvider() + ":" + rr.getModel())
                .basis(basis)
                .dataQuality(ta.getDataQuality()).barsAvailable(ta.getBarsAvailable())
                .seriesStatus(ta.getSeriesStatus()).priceDate(ta.getLastBarDate()).priceSource(ta.getSource())
                .build();
    }

    /** One line reused wherever the trend label is shown next to a rating. */
    static final class TrendNote {
        static final String SHORT = "Descriptive only — a 10-year backtest found no edge from the label on forward returns.";
    }

    /**
     * Fundamentals as facts: value, unit, reporting period, source and fetch time. P/E is
     * recomputed from the last close and trailing EPS so price and earnings refer to the same
     * moment; Yahoo's own trailing P/E (priced at fetch time, up to 7 days old) is the fallback.
     */
    public static List<FundamentalFact> facts(QuoteDto q, TechnicalAnalysisDto ta) {
        List<FundamentalFact> out = new ArrayList<>();
        if (q == null) return out;
        String src = "Yahoo Finance quoteSummary";
        LocalDateTime at = q.getFundamentalsUpdatedAt();
        BigDecimal eps = q.getEps();
        if (eps != null && eps.signum() > 0 && ta.getPrice() != null) {
            out.add(fact("P/E", ta.getPrice().divide(eps, 2, RoundingMode.HALF_UP), "×", "Last close ÷ trailing-12-month EPS",
                    src + " (EPS)", at, "Close of " + ta.getLastBarDate() + " ÷ TTM EPS ₹" + eps));
        } else if (q.getPe() != null) {
            out.add(fact("P/E", q.getPe(), "×", "Trailing 12 months, priced when fetched", src, at, eps != null && eps.signum() <= 0 ? "EPS is not positive" : null));
        } else {
            out.add(fact("P/E", null, "×", "Trailing 12 months", src, at, eps != null && eps.signum() <= 0 ? "Not meaningful — EPS is not positive" : "Unavailable"));
        }
        out.add(fact("EPS", eps, "₹", "Trailing 12 months", src, at, null));
        out.add(fact("P/B", q.getPb(), "×", "Most recent quarter book value", src, at, null));
        out.add(fact("ROE", pct(q.getRoe()), "%", "Trailing 12 months", src, at, null));
        out.add(fact("Debt / equity", q.getDebtToEquity(), "×", "Most recent quarter", src, at, "Yahoo publishes a percentage; converted to a ratio"));
        out.add(fact("Revenue growth", pct(q.getRevenueGrowth()), "%", "Latest quarter vs same quarter a year earlier", src, at, null));
        out.add(fact("Earnings growth", pct(q.getEarningsGrowth()), "%", "Latest quarter vs same quarter a year earlier", src, at, null));
        out.add(fact("Net margin", pct(q.getProfitMargin()), "%", "Trailing 12 months", src, at, null));
        out.add(fact("Current ratio", q.getCurrentRatio(), "×", "Most recent quarter", src, at, null));
        out.add(fact("Market cap", q.getMarketCap(), "₹", "At fetch time", src, at, null));
        // Absolute figures: trailing twelve months to the latest reported quarter.
        String ttm = q.getMostRecentQuarter() != null ? "Trailing 12 months to quarter ending " + q.getMostRecentQuarter() : "Trailing 12 months";
        String cur = q.getFinancialCurrency() != null && !"INR".equalsIgnoreCase(q.getFinancialCurrency()) ? q.getFinancialCurrency() : "₹";
        out.add(fact("Revenue", q.getTotalRevenue(), cur, ttm, src, at, null));
        out.add(fact("EBITDA", q.getEbitda(), cur, ttm, src, at, null));
        out.add(fact("Gross margin", pct(q.getGrossMargin()), "%", ttm, src, at, null));
        out.add(fact("Operating margin", pct(q.getOperatingMargin()), "%", ttm, src, at, null));
        out.add(fact("Operating cash flow", q.getOperatingCashflow(), cur, ttm, src, at, null));
        out.add(fact("Free cash flow", q.getFreeCashflow(), cur, ttm, src, at, null));
        out.add(fact("Total debt", q.getTotalDebt(), cur, "Most recent quarter", src, at, null));
        out.add(fact("Cash & equivalents", q.getTotalCash(), cur, "Most recent quarter", src, at, null));
        // Not published by the data source; listed so the gap is explicit rather than silent.
        out.add(fact("ROCE", null, "%", "—", src, at, "Not available from the data source (needs capital-employed figures)"));
        return out;
    }

    private static FundamentalFact fact(String n, BigDecimal v, String unit, String period, String src, LocalDateTime at, String note) {
        return FundamentalFact.builder().name(n).value(v).unit(unit).period(period).source(src).fetchedAt(at)
                .note(v == null && note == null ? "Unavailable" : note).build();
    }

    private static BigDecimal pct(BigDecimal fraction) {
        return fraction == null ? null : fraction.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private NewsPulse newsSentiment(String base, String name, QuoteDto q) {
        List<NewsSentimentAnalyzer.Headline> headlines = companyNews.forCompany(base, name).headlines();

        Set<String> terms = new LinkedHashSet<>();
        terms.add(base);
        if (name != null) {
            terms.add(name);
            String first = name.split("\\s+")[0];
            if (first.length() >= 4) terms.add(first);
        }
        NewsSentimentAnalyzer.Result r = NewsSentimentAnalyzer.analyse(headlines, terms, LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        List<Article> articles = r.counted().stream().limit(8).map(c -> Article.builder()
                .title(c.headline().title()).url(c.headline().url()).source(c.headline().source())
                .publishedAt(c.headline().publishedAt().toLocalDate().toString())
                .sentiment(c.sentiment()).matchedTerms(c.matched()).event(c.event()).ageDays(c.ageDays()).build()).toList();
        return NewsPulse.builder()
                .category("NEWS_SENTIMENT").status(r.status()).score(r.score()).label(r.label()).confidence(r.confidence())
                .windowDays(NewsSentimentAnalyzer.WINDOW_DAYS)
                .excluded(r.excludedOld() + r.excludedUndated() + r.excludedDuplicate() + r.excludedUnrelated())
                .method(NewsSentimentAnalyzer.METHOD)
                .positive(r.positive()).negative(r.negative()).total(r.considered())
                .headlines(articles.stream().limit(5).map(Article::getTitle).toList())
                .articles(articles)
                .build();
    }

    private List<String> dedup(List<String> in) { List<String> o = new ArrayList<>(); for (String s : in) if (!o.contains(s)) o.add(s); return o; }
}
