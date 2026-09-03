package com.marketai.market.service;

import com.marketai.common.exception.ResourceNotFoundException;
import com.marketai.market.client.YahooFinanceClient;
import com.marketai.market.dto.MarketOverviewDto;
import com.marketai.market.dto.QuoteDto;
import com.marketai.market.entity.MarketIndex;
import com.marketai.market.entity.PriceHistory;
import com.marketai.market.entity.Stock;
import com.marketai.market.repository.MarketIndexRepository;
import com.marketai.market.repository.PriceHistoryRepository;
import com.marketai.market.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataService {

    private final StockRepository stockRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final MarketIndexRepository marketIndexRepository;
    private final YahooFinanceClient yahooClient;

    private static final Map<String, String> INDEX_SYMBOLS;
    static {
        INDEX_SYMBOLS = new HashMap<>();
        INDEX_SYMBOLS.put("NIFTY50", "^NSEI");
        INDEX_SYMBOLS.put("BANKNIFTY", "^NSEBANK");
        INDEX_SYMBOLS.put("SENSEX", "^BSESN");
        INDEX_SYMBOLS.put("NIFTYMIDCAP", "^NSEMDCP50");
    }

    public MarketOverviewDto getMarketOverview() {
        return MarketOverviewDto.builder()
                .nifty50(fetchIndexQuote("NIFTY50", "Nifty 50"))
                .bankNifty(fetchIndexQuote("BANKNIFTY", "Bank Nifty"))
                .sensex(fetchIndexQuote("SENSEX", "Sensex"))
                .niftyMidcap(fetchIndexQuote("NIFTYMIDCAP", "Nifty Midcap 50"))
                .sectors(getSectorPerformance())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    // Was never actually cached despite RedisConfig defining this exact cache name with a
    // 15-min TTL — every call hit Yahoo fresh. Wired up so "cache all market data locally"
    // is real: this method is also the one every Recommendation/Analyst/Forecast call goes
    // through, so caching it caps request volume across the whole app, not just one screen.
    @Cacheable(value = "marketQuotes", key = "#symbol")
    public QuoteDto getQuote(String symbol) {
        Stock stock = stockRepository.findBySymbol(symbol.toUpperCase()).orElse(null);

        // Some symbols (e.g. found via live Yahoo search) are BSE-only — always defaulting
        // to ".NS" produced an invalid ticker and a 404 for those. Use the stock's recorded
        // exchange when we have one, instead of assuming NSE.
        String yahooSymbol;
        if (symbol.endsWith(".NS") || symbol.endsWith(".BO")) {
            yahooSymbol = symbol;
        } else if (stock != null && "BSE".equalsIgnoreCase(stock.getExchange())) {
            yahooSymbol = symbol + ".BO";
        } else {
            yahooSymbol = symbol + ".NS";
        }

        QuoteDto quote = yahooClient.getQuote(yahooSymbol);

        if (stock != null) {
            // P/E, market cap, sector and the rest of the fundamentals aren't in the
            // chart-endpoint quote at all — they come from Yahoo's quoteSummary endpoint and
            // are cached on the Stock row.
            //
            // The old guard was "pe AND marketCap AND sector are all null", so a first fetch
            // that returned only some fields permanently pinned the rest to null — they were
            // never retried. Refresh on staleness instead: unfetched, or older than 7 days.
            if (isFundamentalsStale(stock)) {
                YahooFinanceClient.Fundamentals f = yahooClient.getFundamentals(yahooSymbol);
                if (f != null) {
                    // Null from Yahoo means "unavailable" and is stored as-is; no defaults,
                    // no estimates, no carrying forward a previous value.
                    stock.setPe(f.pe());
                    stock.setMarketCap(f.marketCap());
                    stock.setSector(f.sector() != null && f.sector().length() > 50 ? f.sector().substring(0, 50) : f.sector());
                    stock.setPb(f.pb());
                    stock.setRoe(f.roe());
                    stock.setDebtToEquity(f.debtToEquity());
                    stock.setRevenueGrowth(f.revenueGrowth());
                    stock.setEarningsGrowth(f.earningsGrowth());
                    stock.setProfitMargin(f.profitMargin());
                    stock.setCurrentRatio(f.currentRatio());
                    stock.setEps(f.eps());
                    // Stamped on any successful fetch, including a partial one — the stamp
                    // records when we last asked Yahoo, not how much it gave back.
                    stock.setFundamentalsUpdatedAt(LocalDateTime.now());
                    stock = stockRepository.save(stock);
                }
            }
            quote.setName(stock.getName());
            quote.setSector(stock.getSector());
            quote.setMarketCap(stock.getMarketCap());
            quote.setPe(stock.getPe());
            quote.setPb(stock.getPb());
            quote.setRoe(stock.getRoe());
            quote.setDebtToEquity(stock.getDebtToEquity());
            quote.setRevenueGrowth(stock.getRevenueGrowth());
            quote.setEarningsGrowth(stock.getEarningsGrowth());
            quote.setProfitMargin(stock.getProfitMargin());
            quote.setCurrentRatio(stock.getCurrentRatio());
            quote.setEps(stock.getEps());
            quote.setFundamentalsUpdatedAt(stock.getFundamentalsUpdatedAt());
        }
        quote.setLastUpdated(LocalDateTime.now());
        return quote;
    }

    /** How long a cached fundamentals block stays fresh before we re-ask Yahoo. */
    private static final int FUNDAMENTALS_TTL_DAYS = 7;

    private boolean isFundamentalsStale(Stock stock) {
        LocalDateTime fetchedAt = stock.getFundamentalsUpdatedAt();
        return fetchedAt == null || fetchedAt.isBefore(LocalDateTime.now().minusDays(FUNDAMENTALS_TTL_DAYS));
    }

    public List<Stock> searchStocks(String query) {
        List<Stock> local = stockRepository.searchBySymbolOrName(query);
        if (!local.isEmpty() || query == null || query.trim().length() < 2) return local;

        // Nothing in the curated seed list — fall back to Yahoo's live symbol search so any
        // real NSE/BSE stock is findable, and cache the hit for next time (self-expanding index).
        List<Stock> found = new ArrayList<>();
        for (YahooFinanceClient.SymbolHit hit : yahooClient.searchSymbols(query)) {
            if (hit.symbol().length() > 20) continue;
            Stock stock = stockRepository.findBySymbol(hit.symbol()).orElse(null);
            if (stock == null) {
                stock = stockRepository.save(Stock.builder()
                    .symbol(hit.symbol()).name(hit.name()).exchange(hit.exchange()).active(true).build());
                log.info("Cached new stock from Yahoo search: {} ({})", hit.symbol(), hit.name());
            }
            found.add(stock);
        }
        return found;
    }

    @Transactional(readOnly = true)
    public List<PriceHistory> getPriceHistory(String symbol, LocalDate from, LocalDate to) {
        return priceHistoryRepository.findBySymbolAndDateBetweenOrderByDateAsc(symbol, from, to);
    }

    /** Resolve a stored/friendly symbol to the correct Yahoo Finance ticker. */
    public static String toYahooSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) return symbol;
        String s = symbol.trim();
        if (s.startsWith("^")) return s;                       // already an index ticker
        if (INDEX_SYMBOLS.containsKey(s.toUpperCase())) return INDEX_SYMBOLS.get(s.toUpperCase());
        if (s.endsWith(".NS") || s.endsWith(".BO")) return s;  // already suffixed
        if (s.endsWith(".MF")) return s;                       // mutual fund pseudo-symbol (no yahoo feed)
        return s + ".NS";                                      // default to NSE equity
    }

    @Transactional
    public void fetchAndStorePriceHistory(String symbol, String range) {
        String yahooSymbol = toYahooSymbol(symbol);
        // toYahooSymbol() defaults an unsuffixed symbol to ".NS" — wrong for BSE-only stocks
        // (e.g. ones found via live Yahoo search), which produces an invalid ticker. Correct
        // it using the stock's recorded exchange when we have one.
        if (yahooSymbol.equals(symbol + ".NS")) {
            Stock stock = stockRepository.findBySymbol(symbol.toUpperCase()).orElse(null);
            if (stock != null && "BSE".equalsIgnoreCase(stock.getExchange())) yahooSymbol = symbol + ".BO";
        }
        List<YahooFinanceClient.OhlcvBar> bars = yahooClient.getHistory(yahooSymbol, range, "1d");

        bars.stream()
                .filter(bar -> !priceHistoryRepository.existsBySymbolAndDate(symbol, bar.date()))
                .map(bar -> PriceHistory.builder()
                        .symbol(symbol)
                        .date(bar.date())
                        .open(BigDecimal.valueOf(bar.open()).setScale(2, RoundingMode.HALF_UP))
                        .high(BigDecimal.valueOf(bar.high()).setScale(2, RoundingMode.HALF_UP))
                        .low(BigDecimal.valueOf(bar.low()).setScale(2, RoundingMode.HALF_UP))
                        .close(BigDecimal.valueOf(bar.close()).setScale(2, RoundingMode.HALF_UP))
                        .adjClose(BigDecimal.valueOf(bar.close()).setScale(2, RoundingMode.HALF_UP))
                        .volume(bar.volume())
                        .build())
                .forEach(priceHistoryRepository::save);

        log.info("Stored {} bars for {}", bars.size(), symbol);
    }

    @Scheduled(fixedRateString = "${app.market.refresh-rate-ms:900000}")
    @CacheEvict(value = {"marketQuotes", "indexData", "technicals"}, allEntries = true)
    public void refreshMarketData() {
        log.debug("Market data cache evicted at {}", LocalDateTime.now());
    }

    private MarketOverviewDto.IndexQuote fetchIndexQuote(String key, String name) {
        try {
            QuoteDto q = yahooClient.getQuote(INDEX_SYMBOLS.get(key));
            return MarketOverviewDto.IndexQuote.builder()
                    .symbol(key)
                    .name(name)
                    .value(q.getCurrentPrice())
                    .change(q.getChange())
                    .changePercent(q.getChangePercent())
                    .open(q.getOpen())
                    .high(q.getHigh())
                    .low(q.getLow())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to fetch {} data: {}", key, e.getMessage());
            return MarketOverviewDto.IndexQuote.builder().symbol(key).name(name).build();
        }
    }

    private static final Map<String, String> SECTOR_SYMBOLS = new java.util.LinkedHashMap<>();
    static {
        SECTOR_SYMBOLS.put("IT",          "^CNXIT");
        SECTOR_SYMBOLS.put("Banking",     "^NSEBANK");
        SECTOR_SYMBOLS.put("Auto",        "^CNXAUTO");
        SECTOR_SYMBOLS.put("Pharma",      "^CNXPHARMA");
        SECTOR_SYMBOLS.put("FMCG",        "^CNXFMCG");
        SECTOR_SYMBOLS.put("Metal",       "^CNXMETAL");
        SECTOR_SYMBOLS.put("Realty",      "^CNXREALTY");
        SECTOR_SYMBOLS.put("Energy",      "^CNXENERGY");
        SECTOR_SYMBOLS.put("Infra",       "^CNXINFRA");
        SECTOR_SYMBOLS.put("Media",       "^CNXMEDIA");
    }

    private List<MarketOverviewDto.SectorPerformance> getSectorPerformance() {
        List<MarketOverviewDto.SectorPerformance> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : SECTOR_SYMBOLS.entrySet()) {
            try {
                QuoteDto q = yahooClient.getQuote(entry.getValue());
                BigDecimal chg = q.getChangePercent() != null ? q.getChangePercent() : BigDecimal.ZERO;
                String trend = chg.compareTo(BigDecimal.valueOf(0.5)) > 0 ? "UP"
                             : chg.compareTo(BigDecimal.valueOf(-0.5)) < 0 ? "DOWN" : "NEUTRAL";
                result.add(MarketOverviewDto.SectorPerformance.builder()
                        .sector(entry.getKey())
                        .changePercent(chg)
                        .trend(trend)
                        .build());
            } catch (Exception e) {
                log.debug("Sector fetch failed for {}: {}", entry.getKey(), e.getMessage());
            }
        }
        return result;
    }

    /** Benchmark every sector is measured against. */
    private static final String NIFTY_SYMBOL = "^NSEI";

    /** A return needs a start bar and an end bar; fewer than this and the answer is unknown. */
    private static final int MIN_BARS_FOR_RETURN = 2;

    /**
     * Persist 1y of daily bars for all ten sector indices plus the Nifty benchmark.
     *
     * getSectorPerformance() only ever reports today's move and stores nothing, so sector
     * strength over time could not be computed at all. This backfills the history that
     * getSectorRelativeStrength() reads.
     *
     * Deliberately resilient: one bad ticker (Yahoo 404, timeout, rate limit) must not stop
     * the remaining ones, so each fetch is isolated.
     */
    public void refreshSectorIndexHistory() {
        List<String> tickers = new ArrayList<>(SECTOR_SYMBOLS.values());
        tickers.add(NIFTY_SYMBOL); // benchmark — relative strength is meaningless without it

        int ok = 0;
        for (String ticker : tickers) {
            try {
                fetchAndStorePriceHistory(ticker, "1y");
                ok++;
            } catch (Exception e) {
                log.warn("Sector index history refresh failed for {}: {}", ticker, e.getMessage());
            }
        }
        log.info("Sector index history refresh: {}/{} tickers stored", ok, tickers.size());
    }

    // 18:30 IST, weekdays — after the NSE close (15:30) and after Yahoo has settled the day's
    // final bar. Zone is pinned explicitly so the job does not drift with the server's TZ.
    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledSectorIndexHistoryRefresh() {
        refreshSectorIndexHistory();
    }

    /**
     * Per-sector relative strength over the last {@code lookbackDays}: the sector index
     * return, the Nifty return over the same window, and the difference.
     *
     * Computed purely from stored history — run refreshSectorIndexHistory() first, or the
     * results will be reported as unavailable. Where history is insufficient the returns are
     * null and {@code available} is false; nothing is estimated, defaulted or zero-filled,
     * so "flat versus Nifty" and "we have no idea" stay distinguishable.
     *
     * Does not touch getSectorPerformance(), which other code depends on.
     */
    @Transactional(readOnly = true)
    public List<MarketOverviewDto.SectorRelativeStrength> getSectorRelativeStrength(int lookbackDays) {
        List<MarketOverviewDto.SectorRelativeStrength> result = new ArrayList<>();
        if (lookbackDays < 1) {
            throw new IllegalArgumentException("lookbackDays must be at least 1");
        }

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(lookbackDays);

        List<PriceHistory> niftyBars = priceHistoryRepository
                .findBySymbolAndDateBetweenOrderByDateAsc(NIFTY_SYMBOL, from, to);
        BigDecimal niftyReturn = returnPercentOrNull(niftyBars);

        for (Map.Entry<String, String> entry : SECTOR_SYMBOLS.entrySet()) {
            String sector = entry.getKey();
            String symbol = entry.getValue();

            List<PriceHistory> bars = priceHistoryRepository
                    .findBySymbolAndDateBetweenOrderByDateAsc(symbol, from, to);
            BigDecimal sectorReturn = returnPercentOrNull(bars);

            String reason = null;
            if (sectorReturn == null) {
                reason = "Insufficient stored history for " + symbol
                        + " (" + bars.size() + " bars, need " + MIN_BARS_FOR_RETURN + ")";
            } else if (niftyReturn == null) {
                reason = "Insufficient stored history for benchmark " + NIFTY_SYMBOL
                        + " (" + niftyBars.size() + " bars, need " + MIN_BARS_FOR_RETURN + ")";
            }
            boolean available = reason == null;

            result.add(MarketOverviewDto.SectorRelativeStrength.builder()
                    .sector(sector)
                    .symbol(symbol)
                    .lookbackDays(lookbackDays)
                    // Both sides are reported only when the pair is comparable, so a caller
                    // can never read a sector return next to an absent benchmark.
                    .sectorReturnPercent(available ? sectorReturn : null)
                    .niftyReturnPercent(available ? niftyReturn : null)
                    .relativeStrength(available ? sectorReturn.subtract(niftyReturn) : null)
                    .available(available)
                    .unavailableReason(reason)
                    .barsUsed(bars.size())
                    .benchmarkBarsUsed(niftyBars.size())
                    .build());
        }
        return result;
    }

    /**
     * Percentage move from the first to the last stored close in the window.
     * Returns null — never 0 — when there are too few bars or the base close is unusable.
     */
    private BigDecimal returnPercentOrNull(List<PriceHistory> bars) {
        if (bars == null || bars.size() < MIN_BARS_FOR_RETURN) return null;

        BigDecimal first = bars.get(0).getClose();
        BigDecimal last = bars.get(bars.size() - 1).getClose();
        if (first == null || last == null || first.compareTo(BigDecimal.ZERO) == 0) return null;

        return last.subtract(first)
                .divide(first, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
