package com.marketai.research.service;

import com.marketai.amfi.dto.AmfiNavResult;
import com.marketai.amfi.dto.MfCategoryBucket;
import com.marketai.amfi.service.AmfiNavService;
import com.marketai.analyst.dto.AnalystAssessment;
import com.marketai.analyst.service.AnalystService;
import com.marketai.forecast.dto.ForecastResponse;
import com.marketai.forecast.service.ForecastService;
import com.marketai.market.dto.QuoteDto;
import com.marketai.market.entity.PriceHistory;
import com.marketai.market.entity.Stock;
import com.marketai.market.quality.DailySeries;
import com.marketai.market.quality.DataIssue;
import com.marketai.market.quality.SeriesStatus;
import com.marketai.market.repository.StockRepository;
import com.marketai.market.service.MarketDataService;
import com.marketai.mf.dto.MfPerformanceMetrics;
import com.marketai.mf.service.MfPerformanceService;
import com.marketai.portfolio.dto.PortfolioSummaryDto;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.research.model.*;
import com.marketai.signal.dto.SignalPayload;
import com.marketai.signal.service.SignalEngine;
import com.marketai.technical.dto.PriceLevel;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Builds the verified context research runs on: every number is computed or fetched by code,
 * labelled with its source, date and basis, and given an id the model must cite. Nothing here
 * calls a model. What could not be obtained is listed as missing rather than left out.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResearchContextBuilder {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final List<String> HORIZONS = List.of("1D", "5D", "20D", "60D");

    /** Global and macro series, fetched through the same canonical daily-series pipeline. */
    static final List<String[]> MACRO = List.of(
            new String[]{"INR=X", "USD/INR"},
            new String[]{"BZ=F", "Brent crude (USD/bbl)"},
            new String[]{"^TNX", "US 10-year Treasury yield (%)"},
            new String[]{"^GSPC", "S&P 500"},
            new String[]{"^IXIC", "Nasdaq Composite"},
            new String[]{"^N225", "Nikkei 225"},
            new String[]{"^HSI", "Hang Seng"});

    /** Yahoo sector-index tickers by the sector names Yahoo assigns to Indian listings. */
    static final Map<String, String[]> SECTOR_INDEX = new LinkedHashMap<>();
    static {
        SECTOR_INDEX.put("financial services", new String[]{"^NSEBANK", "Nifty Bank"});
        SECTOR_INDEX.put("technology", new String[]{"^CNXIT", "Nifty IT"});
        SECTOR_INDEX.put("consumer defensive", new String[]{"^CNXFMCG", "Nifty FMCG"});
        SECTOR_INDEX.put("healthcare", new String[]{"^CNXPHARMA", "Nifty Pharma"});
        SECTOR_INDEX.put("consumer cyclical", new String[]{"^CNXAUTO", "Nifty Auto"});
        SECTOR_INDEX.put("basic materials", new String[]{"^CNXMETAL", "Nifty Metal"});
        SECTOR_INDEX.put("energy", new String[]{"^CNXENERGY", "Nifty Energy"});
        SECTOR_INDEX.put("utilities", new String[]{"^CNXENERGY", "Nifty Energy"});
        SECTOR_INDEX.put("real estate", new String[]{"^CNXREALTY", "Nifty Realty"});
        SECTOR_INDEX.put("industrials", new String[]{"^CNXINFRA", "Nifty Infrastructure"});
        SECTOR_INDEX.put("communication services", new String[]{"^CNXMEDIA", "Nifty Media"});
    }

    private final TechnicalIndicatorService technical;
    private final ForecastService forecasts;
    private final SignalEngine signals;
    private final MarketDataService marketData;
    private final StockRepository stocks;
    private final EvidenceRetriever retriever;
    private final PortfolioService portfolios;
    private final HoldingRepository holdings;
    private final MfPerformanceService mfPerformance;
    private final AmfiNavService amfiNav;

    /* ───────────────────────── stock ───────────────────────── */

    public ResearchContext forStock(String symbol, String displayName, Long userId) {
        String sym = MarketDataService.canonicalSymbol(symbol);
        // History is stored under the exchange listing; a bare "RELIANCE" means the NSE one.
        if (!sym.startsWith("^") && !sym.contains(".") && !sym.contains("=")) sym = sym + ".NS";
        String base = sym.replace(".NS", "").replace(".BO", "");
        String name = displayName != null ? displayName : stocks.findBySymbol(base).map(Stock::getName).orElse(base);
        List<Fact> facts = new ArrayList<>();

        TechnicalAnalysisDto ta = technical.analyse(sym);
        QuoteDto q = null;
        try { q = marketData.getQuote(sym); } catch (Exception e) { log.debug("No live quote for {}: {}", sym, e.getMessage()); }

        marketFacts(ta, q, facts);
        technicalFacts(ta, facts);
        List<ForecastResponse> fcs = forecastFacts(sym, name, facts);
        SignalPayload sig = signalFacts(sym, facts);
        fundamentalFacts(q, ta, facts);
        companyFacts(base, q, facts);
        String sectorName = q != null && q.getSector() != null ? q.getSector() : stocks.findBySymbol(base).map(Stock::getSector).orElse(null);
        sectorFacts(sectorName, ta, facts);
        indexFacts(sectorName, facts);
        macroFacts(facts);
        dataQualityFacts(ta, facts);
        if (userId != null) portfolioFacts(userId, sym, base, sectorName, facts);

        EvidenceRetriever.Retrieved ev = retriever.forCompany(base, name);
        sentimentFacts(ev, facts);
        QuantAssessment quant = quant(ta, sig, fcs);
        return assemble("STOCK", base, name, ta.getLastBarDate(), facts, ev, quant, userId);
    }

    /* ───────────────────────── index / market ───────────────────────── */

    public ResearchContext forIndex(String symbol, String displayName) {
        String sym = MarketDataService.canonicalSymbol(symbol);
        String name = displayName != null ? displayName : sym;
        List<Fact> facts = new ArrayList<>();
        TechnicalAnalysisDto ta = technical.analyse(sym);
        marketFacts(ta, null, facts);
        technicalFacts(ta, facts);
        List<ForecastResponse> fcs = forecastFacts(sym, name, facts);
        SignalPayload sig = signalFacts(sym, facts);
        indexFacts("financial services", facts);
        seriesFacts("^INDIAVIX", "India VIX", "MARKET", facts);
        for (Map.Entry<String, String[]> e : new LinkedHashSet<>(SECTOR_INDEX.entrySet())) {
            if (e.getKey().equals("utilities") || e.getKey().equals("financial services")) continue;
            seriesFacts(e.getValue()[0], e.getValue()[1], "SECTOR", facts);
        }
        macroFacts(facts);
        dataQualityFacts(ta, facts);
        facts.add(Fact.missing("MARKET", "FII/DII net flows", "No verified FII/DII flow source is configured"));
        facts.add(Fact.missing("MACRO", "India CPI inflation (latest print)", "No verified macro-data source is configured"));
        facts.add(Fact.missing("MACRO", "RBI policy repo rate", "No verified macro-data source is configured"));

        EvidenceRetriever.Retrieved ev = retriever.forMarket();
        sentimentFacts(ev, facts);
        return assemble("INDEX", sym, name, ta.getLastBarDate(), facts, ev, quant(ta, sig, fcs), null);
    }

    /* ───────────────────────── mutual fund ───────────────────────── */

    public ResearchContext forFund(String symbol, Long userId) {
        List<Fact> facts = new ArrayList<>();
        PortfolioSummaryDto.HoldingDto h = null;
        List<PortfolioSummaryDto.HoldingDto> all = List.of();
        if (userId != null) {
            try {
                all = Optional.ofNullable(portfolios.getCombinedSummary(userId).getHoldings()).orElse(List.of());
                h = all.stream().filter(x -> symbol.equalsIgnoreCase(x.getSymbol())).findFirst().orElse(null);
            } catch (Exception e) { log.debug("Portfolio unavailable for fund research: {}", e.getMessage()); }
        }
        String name = h != null && h.getName() != null ? h.getName() : symbol.replace(".MF", "");
        String schemeCode = null;
        if (h != null) {
            schemeCode = holdings.findByPortfolioIdAndSymbol(h.getPortfolioId(), h.getSymbol()).map(Holding::getAmfiSchemeCode).orElse(null);
        }
        String asOfToday = LocalDate.now(IST).toString();
        AmfiNavResult amfi = null;
        try { amfi = amfiNav.findByName(name); } catch (Exception e) { log.debug("AMFI master unavailable: {}", e.getMessage()); }
        MfCategoryBucket bucket = amfi != null && amfi.getCategoryBucket() != null ? amfi.getCategoryBucket() : MfCategoryBucket.from(name);
        String amc = amfi != null ? amfi.getAmc() : null;
        String catSrc = amfi != null ? "AMFI scheme master" : "Parsed from the scheme name";
        facts.add(Fact.text("FUND", "DATA", "SEBI category", amfi != null ? amfi.getCategory() : null, asOfToday, catSrc));
        facts.add(Fact.text("FUND", amfi != null ? "DATA" : "CALCULATION", "Category bucket", bucket.name(), asOfToday, catSrc));
        facts.add(Fact.text("FUND", "DATA", "Fund house", amc, asOfToday, catSrc));
        facts.add(Fact.text("FUND", "CALCULATION", "Plan", plan(name), asOfToday, "Parsed from the scheme name"));
        facts.add(Fact.text("FUND", "CALCULATION", "Option", option(name), asOfToday, "Parsed from the scheme name"));
        facts.add(Fact.text("FUND", "DATA", "AMFI scheme code", schemeCode, asOfToday, "Linked by name to the AMFI scheme master"));
        if (amfi != null && amfi.getNav() != null)
            facts.add(Fact.of("FUND", "DATA", "Latest NAV", amfi.getNav().doubleValue(), "₹", String.valueOf(amfi.getAsOf()), "AMFI NAVAll"));

        LocalDate marketDate = null;
        MfPerformanceMetrics m = null;
        if (schemeCode != null) {
            try { m = mfPerformance.getPerformance(schemeCode); } catch (Exception e) { log.debug("MF performance unavailable: {}", e.getMessage()); }
        }
        if (m != null) {
            marketDate = m.getLastNavDate();
            String src = "AMFI NAV history (" + m.getObservationCount() + " NAVs from " + m.getFirstNavDate() + ")";
            String at = String.valueOf(m.getLastNavDate());
            facts.add(Fact.of("FUND", "CALCULATION", "1-year return", d(m.getReturn1Y()), "%", at, src));
            facts.add(Fact.of("FUND", "CALCULATION", "3-year CAGR", d(m.getReturn3YCagr()), "%", at, src));
            facts.add(Fact.of("FUND", "CALCULATION", "5-year CAGR", d(m.getReturn5YCagr()), "%", at, src));
            facts.add(Fact.of("FUND", "CALCULATION", "Annualised volatility (daily NAV returns)", d(m.getAnnualisedVolatility()), "%", at, src));
            facts.add(Fact.of("FUND", "CALCULATION", "Maximum drawdown", d(m.getMaxDrawdownPercent()), "%", at, src));
        } else {
            facts.add(Fact.missing("FUND", "NAV history and returns", schemeCode == null ? "Fund is not linked to an AMFI scheme code" : "No stored NAV history"));
        }
        Double nifty1y = trailingReturn("^NSEI", 365);
        facts.add(Fact.of("FUND", "CALCULATION", "Benchmark proxy: Nifty 50 1-year price return", nifty1y, "%",
                marketDate != null ? marketDate.toString() : asOfToday, "Nifty 50 daily closes (Yahoo) — a proxy, not the fund's stated benchmark"));
        for (String missing : List.of("Expense ratio", "AUM", "Fund manager", "Latest portfolio holdings", "Sector allocation",
                "Portfolio overlap with your other funds", "Exit load", "Stated benchmark index")) {
            facts.add(Fact.missing("FUND", missing, "Not available from the configured data sources (needs the fund factsheet)"));
        }
        if (h != null) {
            String at = h.getPriceAsOf() != null ? h.getPriceAsOf().toString() : asOfToday;
            facts.add(Fact.of("PORTFOLIO", "PORTFOLIO", "Units held", d(h.getQuantity()), "units", at, "Your holdings"));
            facts.add(Fact.of("PORTFOLIO", "PORTFOLIO", "Invested", d(h.getInvestedValue()), "₹", at, "Your holdings"));
            facts.add(Fact.of("PORTFOLIO", "PORTFOLIO", "Current value", d(h.getCurrentValue()), "₹", at, "Your holdings (" + h.getValuationBasis() + ")"));
            facts.add(Fact.of("PORTFOLIO", "PORTFOLIO", "Unrealised gain/loss", d(h.getPnlPercent()), "%", at, "Your holdings"));
            facts.add(Fact.of("PORTFOLIO", "PORTFOLIO", "XIRR", d(h.getXirr()), "%", at, "Your transactions"));
            if (h.getBuyDate() != null) {
                long days = ChronoUnit.DAYS.between(h.getBuyDate(), LocalDate.now(IST));
                facts.add(Fact.of("PORTFOLIO", "CALCULATION", "Holding period (since first purchase)", (double) days, "days", asOfToday, "Your transactions"));
                boolean equity = isEquityOriented(bucket);
                facts.add(Fact.text("PORTFOLIO", "CALCULATION", "Tax treatment if redeemed today",
                        equity ? (days >= 365 ? "Long-term (equity-oriented, held ≥ 12 months)" : "Short-term (equity-oriented, held < 12 months)")
                               : "Not assumed equity-oriented (" + bucket + ") — tax treatment depends on the scheme; verify",
                        asOfToday, "Holding period and parsed category — verify with a tax adviser"));
            }
            double mfBook = all.stream().filter(x -> x.getSymbol() != null && x.getSymbol().endsWith(".MF"))
                    .mapToDouble(x -> d0(x.getCurrentValue())).sum();
            if (mfBook > 0) facts.add(Fact.of("PORTFOLIO", "CALCULATION", "Share of your mutual-fund holdings", 100 * d0(h.getCurrentValue()) / mfBook, "%", at, "Your holdings"));
            List<String> sameCat = all.stream().filter(x -> x != null && x.getSymbol() != null && x.getSymbol().endsWith(".MF")
                    && !symbol.equalsIgnoreCase(x.getSymbol()) && bucket == MfCategoryBucket.from(x.getName())).map(PortfolioSummaryDto.HoldingDto::getName).toList();
            facts.add(Fact.text("PORTFOLIO", "PORTFOLIO", "Other funds you hold in the same category", sameCat.isEmpty() ? "None" : String.join("; ", sameCat), asOfToday, "Your holdings"));
        } else {
            facts.add(Fact.missing("PORTFOLIO", "Your holding in this fund", "Not held, or portfolio unavailable"));
        }

        EvidenceRetriever.Retrieved ev = retriever.forFund(name, amc);
        sentimentFacts(ev, facts);
        QuantAssessment quant = new QuantAssessment("NOT_RATED", null, false, null,
                "Mutual funds are not given a directional signal; the figures above are trailing measurements only.",
                null, null, List.of(), null, m != null ? "OK" : "INSUFFICIENT_DATA",
                marketDate != null ? marketDate.toString() : null,
                "No quantitative directional model exists for funds; past returns alone are not a basis for a recommendation.");
        return assemble("MUTUAL_FUND", symbol, name, marketDate, facts, ev, quant, userId);
    }

    /* ───────────────────────── fact groups ───────────────────────── */

    private void marketFacts(TechnicalAnalysisDto ta, QuoteDto q, List<Fact> f) {
        String at = String.valueOf(ta.getLastBarDate());
        f.add(Fact.of("MARKET", "DATA", "Last daily close", d(ta.getPrice()), "₹", at, ta.getSource() + " daily bars (validated)"));
        if (q != null) {
            String qt = q.getMarketTime() != null ? q.getMarketTime().toString() : null;
            f.add(Fact.of("MARKET", "DATA", "Current price (" + q.getPriceType() + ")", d(q.getCurrentPrice()), "₹", qt, "Yahoo Finance quote"));
            f.add(Fact.of("MARKET", "DATA", "Previous close", d(q.getPreviousClose()), "₹", qt, "Yahoo Finance quote"));
            f.add(Fact.of("MARKET", "DATA", "Day change", d(q.getChangePercent()), "%", qt, "Yahoo Finance quote"));
        } else {
            f.add(Fact.missing("MARKET", "Live quote", "The live quote could not be fetched; the last validated daily close is used"));
        }
        f.add(Fact.text("MARKET", "CALCULATION", "Market status", marketStatus(ZonedDateTime.now(IST)),
                ZonedDateTime.now(IST).toLocalDateTime().withNano(0).toString(), "NSE hours 09:15–15:30 IST on weekdays (exchange holidays not checked)"));
    }

    static String marketStatus(ZonedDateTime now) {
        DayOfWeek d = now.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return "Closed (weekend)";
        LocalTime t = now.toLocalTime();
        if (t.isBefore(LocalTime.of(9, 15))) return "Pre-open / closed";
        if (t.isBefore(LocalTime.of(15, 30))) return "Open (unless an exchange holiday)";
        return "Closed for the day";
    }

    private void technicalFacts(TechnicalAnalysisDto ta, List<Fact> f) {
        String at = String.valueOf(ta.getLastBarDate());
        String src = "Computed from " + ta.getBarsAvailable() + " validated daily bars";
        f.add(Fact.of("TECHNICAL", "CALCULATION", "RSI(14), Wilder", d(ta.getRsi()), null, at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "SMA20", d(ta.getSma20()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "SMA50", d(ta.getSma50()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "SMA100", d(ta.getSma100()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "SMA200", d(ta.getSma200()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "EMA20", d(ta.getEma20()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "EMA50", d(ta.getEma50()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "EMA200", d(ta.getEma200()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "MACD line (12/26)", d(ta.getMacd()), null, at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "MACD signal (9)", d(ta.getMacdSignal()), null, at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "MACD histogram", d(ta.getMacdHistogram()), null, at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "ATR(14), Wilder — ₹ per day", d(ta.getAtr()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "ATR as % of price", d(ta.getAtrPct()), "%", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "ADX(14)", d(ta.getAdx()), null, at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "+DI(14)", d(ta.getPlusDi()), null, at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "−DI(14)", d(ta.getMinusDi()), null, at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "Bollinger upper (20, 2σ)", d(ta.getBollingerUpper()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "Bollinger lower (20, 2σ)", d(ta.getBollingerLower()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "Daily volatility (σ of last 120 log returns)", d(ta.getDailyVolatilityPct()), "%", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "Annualised volatility", d(ta.getAnnualizedVolatilityPct()), "%", at, src));
        if (ta.getVolume() != null) {
            f.add(Fact.of("TECHNICAL", "DATA", "Volume (latest session)", ta.getVolume().getLatestVolume() == null ? null : ta.getVolume().getLatestVolume().doubleValue(), "shares", at, src));
            f.add(Fact.of("TECHNICAL", "CALCULATION", "20-session average volume", d(ta.getVolume().getAverageVolume20()), "shares", at, src));
            f.add(Fact.of("TECHNICAL", "CALCULATION", "Relative volume (latest ÷ 20-session average)", d(ta.getVolume().getRelativeVolume()), "×", at, src));
            f.add(Fact.text("TECHNICAL", "CALCULATION", "Volume trend", ta.getVolume().getVolumeTrend(), at, src));
        }
        f.add(Fact.of("TECHNICAL", "CALCULATION", "52-week high", d(ta.getHigh52w()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "52-week low", d(ta.getLow52w()), "₹", at, src));
        f.add(Fact.of("TECHNICAL", "CALCULATION", "Position in 52-week range", d(ta.getRangePosition52wPct()), "%", at, src));
        if (ta.getLevels() != null) {
            level("Nearest support", ta.getLevels().getNearestSupport(), ta.getLevels().getSupportStatus(), at, f);
            level("Next support", ta.getLevels().getNextSupport(), ta.getLevels().getSupportStatus(), at, f);
            level("Nearest resistance", ta.getLevels().getNearestResistance(), ta.getLevels().getResistanceStatus(), at, f);
            level("Next resistance", ta.getLevels().getNextResistance(), ta.getLevels().getResistanceStatus(), at, f);
        }
        if (ta.getTrendAssessment() != null) {
            var t = ta.getTrendAssessment();
            f.add(Fact.text("TECHNICAL", "MODEL", "Trend label (descriptive; no measured forward edge)",
                    t.getLabel() + " — " + t.getBullishVotes() + " bullish / " + t.getBearishVotes() + " bearish of " + t.getVotesAvailable() + " votes", at, "TrendModel"));
        }
    }

    private static void level(String label, PriceLevel p, String status, String at, List<Fact> f) {
        if (p == null) {
            f.add(Fact.missing("TECHNICAL", label, "NO_RELIABLE_LEVEL".equals(status) ? "No reliable level found by the level detector" : "Unavailable"));
            return;
        }
        f.add(Fact.of("TECHNICAL", "CALCULATION", label + " (" + p.getSource() + ", " + p.getDistancePct() + "% away)", d(p.getPrice()), "₹", at, p.getReason()));
    }

    private List<ForecastResponse> forecastFacts(String sym, String name, List<Fact> f) {
        List<ForecastResponse> out = new ArrayList<>();
        for (String h : HORIZONS) {
            ForecastResponse r;
            try { r = forecasts.forecast(sym, h, name); } catch (Exception e) { log.debug("Forecast {} {} failed: {}", sym, h, e.getMessage()); continue; }
            out.add(r);
            String at = String.valueOf(r.getPriceDate());
            String src = "Volatility range model (zero-drift log-normal, σ of the last 120 daily log returns)";
            if (r.getRange90() == null) {
                f.add(Fact.missing("FORECAST", h + " model range", r.getStatusReason() != null ? r.getStatusReason() : "Unavailable"));
                continue;
            }
            f.add(Fact.of("FORECAST", "MODEL", h + " 50% model range — low", r.getRange50().getLow(), "₹", at, src));
            f.add(Fact.of("FORECAST", "MODEL", h + " 50% model range — high", r.getRange50().getHigh(), "₹", at, src));
            f.add(Fact.of("FORECAST", "MODEL", h + " 90% model range — low", r.getRange90().getLow(), "₹", at, src));
            f.add(Fact.of("FORECAST", "MODEL", h + " 90% model range — high", r.getRange90().getHigh(), "₹", at, src));
            if (r.getRange90().getHistoricalCoverage() != null)
                f.add(Fact.of("FORECAST", "CALCULATION", h + " 90% range: share of past windows it held", 100 * r.getRange90().getHistoricalCoverage(), "%", at, "Walk-forward backtest on this instrument"));
            if (r.getRange50().getHistoricalCoverage() != null)
                f.add(Fact.of("FORECAST", "CALCULATION", h + " 50% range: share of past windows it held", 100 * r.getRange50().getHistoricalCoverage(), "%", at, "Walk-forward backtest on this instrument"));
            if (r.getCalibration() != null)
                f.add(Fact.text("FORECAST", "CALCULATION", h + " calibration", r.getCalibration().getLabel() + " — " + r.getCalibration().getSummary(), at, "Walk-forward backtest"));
            if ("EMPIRICAL".equals(r.getProbabilityStatus()) && r.getScenarios() != null) {
                for (ForecastResponse.Scenario s : r.getScenarios()) {
                    if (s.getProbability() != null)
                        f.add(Fact.of("FORECAST", "CALCULATION", h + " " + s.getLabel() + " band: measured historical frequency", s.getProbability(), "%", at,
                                "Share of past outcomes in the " + Math.round(s.getQuantileLow() * 100) + "–" + Math.round(s.getQuantileHigh() * 100) + " percentile band"));
                }
            } else {
                f.add(Fact.missing("FORECAST", h + " scenario probabilities", "Too few independent past windows to measure"));
            }
            if (r.getDirectional() != null && r.getDirectional().getHistoricalUpFrequency() != null)
                f.add(Fact.of("FORECAST", "CALCULATION", h + " share of past windows that ended higher", 100 * r.getDirectional().getHistoricalUpFrequency(), "%", at, "Walk-forward backtest"));
        }
        return out;
    }

    private SignalPayload signalFacts(String sym, List<Fact> f) {
        try {
            SignalPayload s = signals.analyse(sym);
            String at = s.getAsOf() != null ? s.getAsOf().toLocalDate().toString() : null;
            f.add(Fact.text("SIGNAL", "MODEL", "Signal rule reading today", s.getRuleOutput() != null ? s.getRuleOutput().name() : null, at, "SignalEngine " + s.getEngineVersion()));
            f.add(Fact.text("SIGNAL", "MODEL", "Signal shown (after validation)", s.getSignal().name(), at, "SignalEngine validation"));
            SignalPayload.Validation v = s.getValidation();
            if (v != null) {
                String src = "Walk-forward replay, " + v.getHorizonSessions() + "-session outcome, " + v.getObservations() + " sessions";
                f.add(Fact.of("SIGNAL", "CALCULATION", "Past BUY calls", (double) v.getBuyCalls(), "calls", at, src));
                f.add(Fact.of("SIGNAL", "CALCULATION", "Past BUY calls that ended higher", pct(v.getBuyHitRate()), "%", at, src));
                f.add(Fact.of("SIGNAL", "CALCULATION", "Past SELL calls", (double) v.getSellCalls(), "calls", at, src));
                f.add(Fact.of("SIGNAL", "CALCULATION", "Past SELL calls that ended lower", pct(v.getSellHitRate()), "%", at, src));
                f.add(Fact.of("SIGNAL", "CALCULATION", "Base rate: sessions followed by a higher close", pct(v.getBaseUpRate()), "%", at, src));
                f.add(Fact.text("SIGNAL", "CALCULATION", "Current call validated", v.isCurrentCallValidated() ? "yes" : "no", at, src));
            }
            return s;
        } catch (Exception e) {
            f.add(Fact.missing("SIGNAL", "Signal rule", "Signal engine unavailable: " + e.getClass().getSimpleName()));
            return null;
        }
    }

    private void fundamentalFacts(QuoteDto q, TechnicalAnalysisDto ta, List<Fact> f) {
        if (q == null) {
            f.add(Fact.missing("FUNDAMENTAL", "Fundamentals", "The quote and fundamentals could not be fetched"));
            return;
        }
        for (AnalystAssessment.FundamentalFact x : AnalystService.facts(q, ta)) {
            String at = x.getFetchedAt() != null ? x.getFetchedAt().toLocalDate().toString() : null;
            String basis = "P/E".equals(x.getName()) && x.getPeriod() != null && x.getPeriod().startsWith("Last close") ? "CALCULATION" : "DATA";
            String label = x.getName() + (x.getPeriod() != null && !"—".equals(x.getPeriod()) ? " (" + x.getPeriod() + ")" : "");
            if (x.getValue() == null) f.add(Fact.missing("FUNDAMENTAL", label, x.getNote() != null ? x.getNote() : "Unavailable"));
            else f.add(Fact.of("FUNDAMENTAL", basis, label, x.getValue().doubleValue(), unit(x.getUnit()), at, x.getSource()));
        }
        f.add(Fact.text("FUNDAMENTAL", "DATA", "Latest reported quarter (period end)", q.getMostRecentQuarter() != null ? q.getMostRecentQuarter().toString() : null,
                q.getFundamentalsUpdatedAt() != null ? q.getFundamentalsUpdatedAt().toLocalDate().toString() : null, "Yahoo Finance quoteSummary"));
    }

    private static String unit(String u) {
        if (u == null) return null;
        return switch (u) { case "×" -> "×"; case "₹" -> "₹"; case "%" -> "%"; default -> u; };
    }

    private void companyFacts(String base, QuoteDto q, List<Fact> f) {
        Stock s = stocks.findBySymbol(base).orElse(null);
        String at = LocalDate.now(IST).toString();
        f.add(Fact.text("COMPANY", "DATA", "Company name", s != null ? s.getName() : q != null ? q.getName() : null, at, "Stock master"));
        f.add(Fact.text("COMPANY", "DATA", "Sector", q != null && q.getSector() != null ? q.getSector() : s != null ? s.getSector() : null, at, "Yahoo Finance assetProfile"));
        f.add(Fact.text("COMPANY", "DATA", "Industry", q != null && q.getIndustry() != null ? q.getIndustry() : s != null ? s.getIndustry() : null, at, "Yahoo Finance assetProfile"));
        f.add(Fact.text("COMPANY", "DATA", "Exchange", s != null ? s.getExchange() : "NSE", at, "Stock master"));
    }

    private void sectorFacts(String sector, TechnicalAnalysisDto ta, List<Fact> f) {
        String[] idx = sector == null ? null : SECTOR_INDEX.get(sector.toLowerCase(Locale.ROOT));
        if (idx == null) {
            f.add(Fact.missing("SECTOR", "Sector index", sector == null ? "Sector unknown" : "No sector index mapped for " + sector));
            return;
        }
        Double sector20 = seriesFacts(idx[0], idx[1] + " (sector index)", "SECTOR", f);
        Double stock20 = trailingSessions(ta.getSymbol(), 20);
        if (sector20 != null && stock20 != null) {
            f.add(Fact.of("SECTOR", "CALCULATION", "Stock 20-session return minus " + idx[1] + " 20-session return", stock20 - sector20, "%", String.valueOf(ta.getLastBarDate()), "Daily closes"));
        }
    }

    private void indexFacts(String sector, List<Fact> f) {
        seriesFacts("^NSEI", "Nifty 50", "MARKET", f);
        try {
            TechnicalAnalysisDto n = technical.analyse("^NSEI");
            if (n.getTrendAssessment() != null)
                f.add(Fact.text("MARKET", "MODEL", "Nifty 50 trend label (descriptive)", n.getTrendAssessment().getLabel(), String.valueOf(n.getLastBarDate()), "TrendModel on Nifty 50"));
            f.add(Fact.of("MARKET", "CALCULATION", "Nifty 50 annualised volatility (120 sessions)", d(n.getAnnualizedVolatilityPct()), "%", String.valueOf(n.getLastBarDate()), "Nifty 50 daily closes"));
        } catch (Exception e) { log.debug("Nifty technicals unavailable: {}", e.getMessage()); }
        if (sector != null && sector.toLowerCase(Locale.ROOT).contains("financial")) seriesFacts("^NSEBANK", "Nifty Bank", "MARKET", f);
        seriesFacts("^INDIAVIX", "India VIX", "MARKET", f);
    }

    private void macroFacts(List<Fact> f) {
        for (String[] m : MACRO) seriesFacts(m[0], m[1], "MACRO", f);
    }

    /**
     * Last close and 5/20-session change of a series from the canonical pipeline.
     * @return the 20-session % change, or null
     */
    private Double seriesFacts(String sym, String label, String category, List<Fact> f) {
        DailySeries s;
        try { s = marketData.getDailySeries(sym, 21); } catch (Exception e) { s = null; }
        if (s == null || s.isEmpty() || s.status() == SeriesStatus.INSUFFICIENT_DATA) {
            f.add(Fact.missing(category, label, "No validated daily history available"));
            return null;
        }
        List<Double> c = s.closes();
        String at = String.valueOf(s.lastBarDate()) + (s.stale() ? " (stale)" : "");
        String src = (s.provider() != null ? s.provider() : "stored") + " daily closes";
        double last = c.get(c.size() - 1);
        f.add(Fact.of(category, "DATA", label + " — last close", last, null, at, src));
        Double r5 = c.size() > 5 ? 100 * (last / c.get(c.size() - 6) - 1) : null;
        Double r20 = c.size() > 20 ? 100 * (last / c.get(c.size() - 21) - 1) : null;
        f.add(Fact.of(category, "CALCULATION", label + " — 5-session change", r5, "%", at, src));
        f.add(Fact.of(category, "CALCULATION", label + " — 20-session change", r20, "%", at, src));
        return r20;
    }

    private Double trailingSessions(String sym, int n) {
        try {
            DailySeries s = marketData.getDailySeries(sym, n + 1);
            List<Double> c = s.closes();
            return c.size() > n ? 100 * (c.get(c.size() - 1) / c.get(c.size() - 1 - n) - 1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Double trailingReturn(String sym, int calendarDays) {
        try {
            DailySeries s = marketData.getDailySeries(sym, 200);
            List<PriceHistory> b = s.bars();
            if (b.isEmpty()) return null;
            LocalDate cutoff = b.get(b.size() - 1).getDate().minusDays(calendarDays);
            PriceHistory start = null;
            for (PriceHistory p : b) if (!p.getDate().isAfter(cutoff)) start = p;
            if (start == null) return null;
            return 100 * (b.get(b.size() - 1).getClose().doubleValue() / start.getClose().doubleValue() - 1);
        } catch (Exception e) {
            return null;
        }
    }

    private void dataQualityFacts(TechnicalAnalysisDto ta, List<Fact> f) {
        String at = String.valueOf(ta.getLastBarDate());
        f.add(Fact.text("DATA_QUALITY", "CALCULATION", "Price-series status", ta.getSeriesStatus(), at, "PriceSeriesValidator"));
        f.add(Fact.of("DATA_QUALITY", "DATA", "Validated daily bars", ta.getBarsAvailable() == null ? null : ta.getBarsAvailable().doubleValue(), "bars", at, "PriceSeriesValidator"));
        f.add(Fact.text("DATA_QUALITY", "DATA", "Expected latest session", String.valueOf(ta.getExpectedSession()), at, "NSE session calendar (weekdays)"));
        LocalDate window = ta.getLastBarDate() != null ? ta.getLastBarDate().minusYears(1) : null;
        List<String> recent = new ArrayList<>();
        if (ta.getDataIssues() != null) for (DataIssue i : ta.getDataIssues()) {
            if (i.isWarning() && (i.date() == null || window == null || !i.date().isBefore(window))) recent.add(i.code() + (i.date() != null ? " " + i.date() : "") + ": " + i.detail());
        }
        f.add(Fact.text("DATA_QUALITY", "CALCULATION", "Data warnings in the last year", recent.isEmpty() ? "None" : String.join(" | ", recent), at, "PriceSeriesValidator"));
    }

    private void portfolioFacts(Long userId, String sym, String base, String sector, List<Fact> f) {
        List<PortfolioSummaryDto.HoldingDto> all;
        try {
            all = Optional.ofNullable(portfolios.getCombinedSummary(userId).getHoldings()).orElse(List.of());
        } catch (Exception e) {
            f.add(Fact.missing("PORTFOLIO", "Portfolio", "Portfolio could not be loaded"));
            return;
        }
        String at = LocalDate.now(IST).toString();
        List<PortfolioSummaryDto.HoldingDto> stocksHeld = all.stream().filter(h -> h.getSymbol() != null && !h.getSymbol().endsWith(".MF")).toList();
        double book = stocksHeld.stream().mapToDouble(h -> d0(h.getCurrentValue())).sum();
        PortfolioSummaryDto.HoldingDto h = stocksHeld.stream().filter(x -> sym.equalsIgnoreCase(x.getSymbol()) || base.equalsIgnoreCase(x.getSymbol())).findFirst().orElse(null);
        if (h == null) {
            f.add(Fact.text("PORTFOLIO", "PORTFOLIO", "Your position", "Not held", at, "Your holdings"));
        } else {
            String pa = h.getPriceAsOf() != null ? h.getPriceAsOf().toString() : at;
            f.add(Fact.of("PORTFOLIO", "PORTFOLIO", "Shares held", d(h.getQuantity()), "shares", pa, "Your holdings"));
            f.add(Fact.of("PORTFOLIO", "PORTFOLIO", "Average cost", d(h.getAverageCost()), "₹", pa, "Your holdings"));
            f.add(Fact.of("PORTFOLIO", "PORTFOLIO", "Position value", d(h.getCurrentValue()), "₹", pa, "Your holdings (" + h.getValuationBasis() + ")"));
            f.add(Fact.of("PORTFOLIO", "PORTFOLIO", "Unrealised gain/loss", d(h.getPnlPercent()), "%", pa, "Your holdings"));
            if (book > 0) f.add(Fact.of("PORTFOLIO", "CALCULATION", "Share of your stock holdings", 100 * d0(h.getCurrentValue()) / book, "%", pa, "Your holdings"));
            if (h.getBuyDate() != null)
                f.add(Fact.of("PORTFOLIO", "CALCULATION", "Holding period (since first purchase)", (double) ChronoUnit.DAYS.between(h.getBuyDate(), LocalDate.now(IST)), "days", at, "Your transactions"));
        }
        if (book > 0) {
            double top = stocksHeld.stream().mapToDouble(x -> d0(x.getCurrentValue())).max().orElse(0);
            f.add(Fact.of("PORTFOLIO", "CALCULATION", "Largest single stock as share of your stock holdings", 100 * top / book, "%", at, "Your holdings"));
            f.add(Fact.of("PORTFOLIO", "PORTFOLIO", "Stocks held", (double) stocksHeld.size(), "stocks", at, "Your holdings"));
            if (sector != null) {
                List<String> same = new ArrayList<>();
                double sectorValue = 0;
                for (PortfolioSummaryDto.HoldingDto x : stocksHeld) {
                    String xb = x.getSymbol().replace(".NS", "").replace(".BO", "");
                    String xs = stocks.findBySymbol(xb).map(Stock::getSector).orElse(null);
                    if (sector.equalsIgnoreCase(xs)) {
                        sectorValue += d0(x.getCurrentValue());
                        if (!xb.equalsIgnoreCase(base)) same.add(x.getName() != null ? x.getName() : xb);
                    }
                }
                f.add(Fact.of("PORTFOLIO", "CALCULATION", "Your exposure to " + sector + " (share of stock holdings)", 100 * sectorValue / book, "%", at, "Your holdings × sector from the stock master"));
                f.add(Fact.text("PORTFOLIO", "PORTFOLIO", "Other " + sector + " stocks you hold", same.isEmpty() ? "None" : String.join("; ", same), at, "Your holdings"));
            }
        }
        long funds = all.stream().filter(x -> x.getSymbol() != null && x.getSymbol().endsWith(".MF")).count();
        f.add(Fact.text("PORTFOLIO", "PORTFOLIO", "Mutual funds held (may own this stock — fund portfolios are not loaded)", funds + " fund(s)", at, "Your holdings"));
        f.add(Fact.missing("PORTFOLIO", "Planned investment in this stock", "Not recorded in the investment plan"));
    }

    private void sentimentFacts(EvidenceRetriever.Retrieved ev, List<Fact> f) {
        var r = ev.sentiment();
        String at = LocalDate.now(IST).toString();
        if ("OK".equals(r.status())) {
            f.add(Fact.of("NEWS", "CALCULATION", "Keyword headline sentiment score (−100..100)", r.score() == null ? null : r.score().doubleValue(), null, at,
                    r.considered() + " headlines, last " + com.marketai.news.service.NewsSentimentAnalyzer.WINDOW_DAYS + " days; " + r.confidence() + " confidence keyword lexicon"));
        } else {
            f.add(Fact.missing("NEWS", "Keyword headline sentiment", "Fewer than " + com.marketai.news.service.NewsSentimentAnalyzer.MIN_ARTICLES + " recent, relevant headlines"));
        }
    }

    private QuantAssessment quant(TechnicalAnalysisDto ta, SignalPayload sig, List<ForecastResponse> fcs) {
        String rating;
        Integer hit = null;
        boolean validated = false;
        SignalPayload.Validation v = sig != null ? sig.getValidation() : null;
        if (sig == null) rating = "INSUFFICIENT_DATA";
        else switch (sig.getSignal()) {
            case BUY, SELL -> {
                rating = sig.getSignal().name();
                validated = true;
                Double r = sig.getSignal() == SignalPayload.Type.BUY ? v.getBuyHitRate() : v.getSellHitRate();
                hit = r == null ? null : (int) Math.round(r * 100);
            }
            case STALE_DATA -> rating = "STALE_DATA";
            case INSUFFICIENT_DATA -> rating = "INSUFFICIENT_DATA";
            default -> rating = "NO_ACTIONABLE_SIGNAL";
        }
        List<String> fsum = new ArrayList<>();
        String cal = null;
        for (ForecastResponse r : fcs) {
            if (r.getRange90() != null) {
                fsum.add(String.format(Locale.ROOT, "%s: 90%% model range ₹%.2f–₹%.2f (held in %s of past windows)", r.getHorizon(),
                        r.getRange90().getLow(), r.getRange90().getHigh(),
                        r.getRange90().getHistoricalCoverage() == null ? "unmeasured" : String.format(Locale.ROOT, "%.1f%%", 100 * r.getRange90().getHistoricalCoverage())));
                if ("20D".equals(r.getHorizon()) && r.getCalibration() != null) cal = r.getCalibration().getSummary();
            } else {
                fsum.add(r.getHorizon() + ": no range — " + r.getStatusReason());
            }
        }
        String trend = ta.getTrendAssessment() != null ? ta.getTrendAssessment().getLabel() : ta.getTrend();
        String summary = validated
                ? "Validated " + rating + " call (historically right " + hit + "% of the time on this instrument)."
                : switch (rating) {
                    case "STALE_DATA" -> "Price data is stale; no quantitative call.";
                    case "INSUFFICIENT_DATA" -> "Not enough validated price history for a quantitative call.";
                    default -> "No validated directional edge: the signal rule's calls have not beaten the base rate on this instrument.";
                };
        return new QuantAssessment(rating, sig != null && sig.getRuleOutput() != null ? sig.getRuleOutput().name() : null, validated, hit,
                v != null ? v.getSummary() : null, trend,
                ta.getTrendAssessment() != null ? ta.getTrendAssessment().getBullishVotes() + " bullish / " + ta.getTrendAssessment().getBearishVotes() + " bearish votes (descriptive)" : null,
                fsum, cal, ta.getSeriesStatus(), String.valueOf(ta.getLastBarDate()), summary);
    }

    /* ───────────────────────── assembly ───────────────────────── */

    private ResearchContext assemble(String type, String sym, String name, LocalDate marketDate, List<Fact> facts,
                                     EvidenceRetriever.Retrieved ev, QuantAssessment quant, Long userId) {
        List<Fact> numbered = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) numbered.add(facts.get(i).withId("F" + (i + 1)));
        List<Evidence> evidence = new ArrayList<>();
        for (int i = 0; i < ev.evidence().size(); i++) evidence.add(ev.evidence().get(i).withId("E" + (i + 1)));
        List<String> missing = numbered.stream().filter(x -> !x.available()).map(x -> x.label() + (x.source() != null ? " — " + x.source() : "")).toList();
        String hash = hash(type, sym, marketDate, numbered, evidence, quant, userId);
        return new ResearchContext(type, sym, name, marketDate != null ? marketDate.toString() : null, numbered, evidence,
                ev.sources(), missing, quant, userId, hash);
    }

    /** Values, dates and sources only — ids and retrieval times are left out so the same inputs hash the same. */
    static String hash(String type, String sym, LocalDate marketDate, List<Fact> facts, List<Evidence> evidence, QuantAssessment q, Long userId) {
        StringBuilder sb = new StringBuilder().append(type).append('|').append(sym).append('|').append(marketDate).append('|').append(userId).append('\n');
        for (Fact f : facts) {
            // Wall-clock facts would change the hash every minute; they are not new information.
            if ("Market status".equals(f.label())) continue;
            sb.append(f.category()).append('|').append(f.label()).append('|').append(f.value()).append('|').append(f.asOf()).append('\n');
        }
        List<String> ev = new ArrayList<>();
        for (Evidence e : evidence) ev.add(e.kind() + '|' + e.source() + '|' + e.title() + '|' + e.publishedAt());
        Collections.sort(ev);
        ev.forEach(x -> sb.append(x).append('\n'));
        if (q != null) sb.append(q.rating()).append('|').append(q.ruleOutput()).append('|').append(q.forecastSummary());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String plan(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.contains("direct") ? "Direct" : n.contains("regular") ? "Regular" : null;
    }

    static String option(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.contains("idcw") || n.contains("dividend") ? "IDCW" : n.contains("growth") ? "Growth" : null;
    }

    /** Equity-oriented for tax purposes; hybrid, international and "other" are not assumed to be. */
    static boolean isEquityOriented(MfCategoryBucket b) {
        return switch (b) {
            case LARGE_CAP, MID_CAP, SMALL_CAP, LARGE_AND_MID_CAP, FLEXI_CAP, MULTI_CAP, ELSS, INDEX, SECTORAL_THEMATIC -> true;
            default -> false;
        };
    }

    private static Double d(BigDecimal b) { return b == null ? null : b.doubleValue(); }
    private static double d0(BigDecimal b) { return b == null ? 0 : b.doubleValue(); }
    private static Double pct(Double fraction) { return fraction == null ? null : 100 * fraction; }
}
