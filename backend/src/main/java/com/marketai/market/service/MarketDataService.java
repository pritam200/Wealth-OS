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

    /**
     * Batch writer for new history rows. Optional so the service still works (one row at a time
     * through JPA) where no JDBC template is wired, as in unit tests.
     */
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJdbcTemplate(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final Map<String, String> INDEX_SYMBOLS;
    static {
        INDEX_SYMBOLS = new HashMap<>();
        INDEX_SYMBOLS.put("NIFTY50", "^NSEI");
        INDEX_SYMBOLS.put("BANKNIFTY", "^NSEBANK");
        INDEX_SYMBOLS.put("SENSEX", "^BSESN");
        INDEX_SYMBOLS.put("NIFTYMIDCAP", "^NSEMDCP50");
        INDEX_SYMBOLS.put("INDIAVIX", "^INDIAVIX");
    }

    /**
     * The header ticker asks every minute from every open page; each answer is a dozen Yahoo
     * calls (~1.5 s). One answer serves everyone for a minute.
     */
    private volatile MarketOverviewDto lastOverview;

    public MarketOverviewDto getMarketOverview() {
        MarketOverviewDto last = lastOverview;
        if (last != null && last.getLastUpdated() != null && last.getLastUpdated().isAfter(LocalDateTime.now().minusSeconds(60))) {
            return last;
        }
        MarketOverviewDto fresh = fetchMarketOverview();
        lastOverview = fresh;
        return fresh;
    }

    private MarketOverviewDto fetchMarketOverview() {
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
        if (symbol.startsWith("^") || INDEX_SYMBOLS.containsKey(symbol.trim().toUpperCase())) {
            // Indices: "NIFTY50" + ".NS" and "^NSEI.NS" are not tickers; the quote was silently lost.
            yahooSymbol = toYahooSymbol(symbol);
        } else if (symbol.endsWith(".NS") || symbol.endsWith(".BO")) {
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
                    stock.setFundamentalsVersion(Stock.FUNDAMENTALS_VERSION);
                    stock.setRevenueGrowth(f.revenueGrowth());
                    stock.setEarningsGrowth(f.earningsGrowth());
                    stock.setProfitMargin(f.profitMargin());
                    stock.setCurrentRatio(f.currentRatio());
                    stock.setEps(f.eps());
                    if (f.industry() != null) stock.setIndustry(f.industry().length() > 100 ? f.industry().substring(0, 100) : f.industry());
                    stock.setTotalRevenue(f.totalRevenue());
                    stock.setEbitda(f.ebitda());
                    stock.setOperatingCashflow(f.operatingCashflow());
                    stock.setFreeCashflow(f.freeCashflow());
                    stock.setTotalDebt(f.totalDebt());
                    stock.setTotalCash(f.totalCash());
                    stock.setGrossMargin(f.grossMargin());
                    stock.setOperatingMargin(f.operatingMargin());
                    stock.setFinancialCurrency(f.financialCurrency());
                    stock.setMostRecentQuarter(f.mostRecentQuarter());
                    stock.setLastFiscalYearEnd(f.lastFiscalYearEnd());
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
            quote.setDebtToEquity(stock.debtToEquityRatio());
            quote.setRevenueGrowth(stock.getRevenueGrowth());
            quote.setEarningsGrowth(stock.getEarningsGrowth());
            quote.setProfitMargin(stock.getProfitMargin());
            quote.setCurrentRatio(stock.getCurrentRatio());
            quote.setEps(stock.getEps());
            quote.setFundamentalsUpdatedAt(stock.getFundamentalsUpdatedAt());
            quote.setIndustry(stock.getIndustry());
            quote.setTotalRevenue(stock.getTotalRevenue());
            quote.setEbitda(stock.getEbitda());
            quote.setOperatingCashflow(stock.getOperatingCashflow());
            quote.setFreeCashflow(stock.getFreeCashflow());
            quote.setTotalDebt(stock.getTotalDebt());
            quote.setTotalCash(stock.getTotalCash());
            quote.setGrossMargin(stock.getGrossMargin());
            quote.setOperatingMargin(stock.getOperatingMargin());
            quote.setFinancialCurrency(stock.getFinancialCurrency());
            quote.setMostRecentQuarter(stock.getMostRecentQuarter());
            quote.setLastFiscalYearEnd(stock.getLastFiscalYearEnd());
        }
        quote.setLastUpdated(LocalDateTime.now());
        return quote;
    }

    /** How long a cached fundamentals block stays fresh before we re-ask Yahoo. */
    private static final int FUNDAMENTALS_TTL_DAYS = 7;

    private boolean isFundamentalsStale(Stock stock) {
        LocalDateTime fetchedAt = stock.getFundamentalsUpdatedAt();
        return fetchedAt == null || fetchedAt.isBefore(LocalDateTime.now().minusDays(FUNDAMENTALS_TTL_DAYS))
                // Rows stored before D/E was converted to a ratio are re-fetched once.
                || stock.getFundamentalsVersion() == null || stock.getFundamentalsVersion() < Stock.FUNDAMENTALS_VERSION;
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

    /**
     * Stored daily bars for the price chart, one per date, oldest first. Read under every name
     * the instrument is stored as, and collapsed the way the validator does, so a duplicated
     * history no longer draws each day twice.
     */
    @Transactional(readOnly = true)
    public List<PriceHistory> getPriceHistory(String symbol, LocalDate from, LocalDate to) {
        String s = canonicalSymbol(symbol);
        return com.marketai.market.quality.PriceSeriesValidator.latestPerDate(
                priceHistoryRepository.findDailyBySymbolsAndDateBetween(storedNames(s), from, to));
    }

    /** Resolve a stored/friendly symbol to the correct Yahoo Finance ticker. */
    public static String toYahooSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) return symbol;
        String s = symbol.trim();
        if (s.startsWith("^")) return s;                       // already an index ticker
        if (s.contains("=")) return s;                         // FX (INR=X) and futures (BZ=F) tickers
        if (INDEX_SYMBOLS.containsKey(s.toUpperCase())) return INDEX_SYMBOLS.get(s.toUpperCase());
        if (s.endsWith(".NS") || s.endsWith(".BO")) return s;  // already suffixed
        if (s.endsWith(".MF")) return s;                       // mutual fund pseudo-symbol (no yahoo feed)
        return s + ".NS";                                      // default to NSE equity
    }

    /** IST — every "has the session closed" decision is made on the exchange clock. */
    static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");
    /** NSE closes at 15:30; Yahoo has settled the day's bar a few minutes later. */
    private static final java.time.LocalTime SESSION_SETTLED = java.time.LocalTime.of(15, 45);

    /**
     * The most recent weekday whose session has finished at {@code now}. Exchange holidays are
     * not modelled, so on a holiday this names a date with no bar; the caller's fetch then
     * simply finds nothing new.
     */
    public static LocalDate lastCompletedSession(java.time.ZonedDateTime now) {
        java.time.ZonedDateTime ist = now.withZoneSameInstant(IST);
        LocalDate d = ist.toLocalDate();
        if (ist.toLocalTime().isBefore(SESSION_SETTLED)) d = d.minusDays(1);
        while (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** Weekdays strictly after {@code from} up to and including {@code to}. */
    public static int weekdaysBetween(LocalDate from, LocalDate to) {
        return com.marketai.market.quality.PriceSeriesValidator.weekdaysBetween(from, to);
    }

    /** Stored daily bars read for analysis — ~10 years, enough for indicators and a walk-forward backtest. */
    public static final int SERIES_MAX_BARS = 2600;

    /**
     * Calendar days read back from the newest bar to find {@link #SERIES_MAX_BARS} sessions
     * (~248 a year, so about 10.5 years). The series is read by date range rather than by row
     * count: duplicate rows used up a row-count allowance, so a history stored twice came back
     * as ~1,300 days instead of 2,600.
     */
    static final int SERIES_WINDOW_DAYS = 3900;

    /**
     * The canonical daily series for {@code symbol}: history topped up to the last completed
     * session, then validated (see {@link com.marketai.market.quality.PriceSeriesValidator}).
     * Every indicator, trend, level, forecast and signal is computed from this, so they all
     * see the same bars and the same data-quality verdict.
     *
     * <p>When the top-up had to wait for other symbols and there is not yet enough history, the
     * series carries a {@link com.marketai.market.quality.DailySeries#HISTORY_PENDING} issue: the
     * download is queued, so "no data" here means "not yet", and callers can say so.
     *
     * @param minBars fewer valid bars than this ⇒ status INSUFFICIENT_DATA
     */
    public com.marketai.market.quality.DailySeries getDailySeries(String symbol, int minBars) {
        String s = canonicalSymbol(symbol);
        Freshness freshness = ensureFreshDailyHistory(s);
        List<PriceHistory> rows = dailyRows(s, SERIES_MAX_BARS);
        LocalDate expected = lastCompletedSession(java.time.ZonedDateTime.now(IST));
        com.marketai.market.quality.DailySeries series =
                com.marketai.market.quality.PriceSeriesValidator.validate(s, rows, expected, minBars);
        if (freshness == Freshness.DEFERRED && series.status() == com.marketai.market.quality.SeriesStatus.INSUFFICIENT_DATA) {
            series = series.withIssue(new com.marketai.market.quality.DataIssue(
                    com.marketai.market.quality.DailySeries.HISTORY_PENDING, com.marketai.market.quality.DataIssue.INFO, null,
                    "Price history for " + s + " is still downloading behind other symbols — it will be ready shortly."));
        }
        return series;
    }

    /**
     * The newest {@code maxBars} sessions of daily rows stored under any of the symbol's names.
     * Duplicate rows are left in for the validator, which keeps one per date and reports them.
     */
    List<PriceHistory> dailyRows(String canonical, int maxBars) {
        List<String> names = storedNames(canonical);
        PriceHistory newest = newestDaily(names);
        if (newest == null) return List.of();
        List<PriceHistory> rows = priceHistoryRepository.findDailyBySymbolsAndDateBetween(
                names, newest.getDate().minusDays(SERIES_WINDOW_DAYS), newest.getDate());
        java.util.TreeSet<LocalDate> dates = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        for (PriceHistory p : rows) dates.add(p.getDate());
        if (dates.size() <= maxBars) return rows;
        LocalDate cutoff = new ArrayList<>(dates).get(maxBars - 1);
        return rows.stream().filter(p -> !p.getDate().isBefore(cutoff)).toList();
    }

    /** Newest stored daily bar under any of {@code names}; intraday rows never count. */
    private PriceHistory newestDaily(List<String> names) {
        List<PriceHistory> top = priceHistoryRepository.findNewestDaily(names,
                org.springframework.data.domain.PageRequest.of(0, 1));
        return top == null || top.isEmpty() ? null : top.get(0);
    }

    /**
     * One stored name per instrument: upper-cased, index aliases (NIFTY50, BANKNIFTY …)
     * resolved to their exchange ticker, and the NSE suffix dropped — NSE is the default
     * listing, so "RELIANCE" and "RELIANCE.NS" are one history rather than two with different
     * freshness. A BSE listing keeps its ".BO".
     */
    public static String canonicalSymbol(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase();
        s = INDEX_SYMBOLS.getOrDefault(s, s);
        if (s.endsWith(".NS") && s.length() > 3) s = s.substring(0, s.length() - 3);
        return s;
    }

    /**
     * Every name a canonical symbol's daily history may be stored under. Rows written before
     * {@link #canonicalSymbol} dropped ".NS" sit under "RELIANCE.NS"; they are read alongside the
     * "RELIANCE" rows rather than rewritten, and new bars go under the canonical name.
     */
    public static List<String> storedNames(String canonical) {
        if (canonical == null) return List.of();
        boolean plainEquity = !canonical.startsWith("^") && !canonical.contains(".") && !canonical.contains("=");
        return plainEquity ? List.of(canonical, canonical + ".NS") : List.of(canonical);
    }

    /** What a history top-up did. */
    public enum Freshness {
        /** Already had the last completed session. */
        CURRENT,
        /** Fetched, and the history moved forward. */
        FETCHED,
        /** Fetched, but the provider had nothing newer (a holiday, a suspension, an outage). */
        NOTHING_NEW,
        /** Other symbols held every download slot; this one is queued in the background. */
        DEFERRED,
        /** Not fetched: no price feed (funds), or a fetch for this session already found nothing. */
        SKIPPED,
        FAILED
    }

    /**
     * Bring a symbol's stored daily history up to the last completed session.
     *
     * Every analysis used to fetch history only when fewer than 20 bars were stored, and no
     * job refreshed stock history, so after a symbol's first fetch its price, RSI, moving
     * averages, trend and forecast stayed frozen at that date for good. This tops the history
     * up whenever the newest bar is behind, fetching only as much range as the gap needs.
     * Failures are logged, never thrown: stale data is then reported as stale by the caller.
     */
    public Freshness ensureFreshDailyHistory(String symbol) {
        return freshen(canonicalSymbol(symbol), false);
    }

    /** @param waitForTurn wait for a download slot however long it takes (background fills) */
    private Freshness freshen(String symbol, boolean waitForTurn) {
        // Funds are priced from AMFI NAVs; there is no exchange feed to ask.
        if (symbol == null || symbol.endsWith(".MF")) return Freshness.SKIPPED;
        try {
            LocalDate expected = lastCompletedSession(java.time.ZonedDateTime.now(IST));
            List<String> names = storedNames(symbol);
            PriceHistory newest = newestDaily(names);
            if (newest != null && !newest.getDate().isBefore(expected)) return Freshness.CURRENT;
            if (recentlyFoundNothing(symbol, expected)) return Freshness.SKIPPED;
            String range;
            if (newest == null) range = "10y";
            else {
                int gap = weekdaysBetween(newest.getDate(), expected);
                range = gap <= 4 ? "5d" : gap <= 20 ? "1mo" : gap <= 60 ? "3mo" : "1y";
            }
            // A first fetch writes ~2,500 rows, holding a pooled connection throughout. A holdings
            // page asks for dozens of symbols at once, and letting them all run pinned the whole
            // pool, so every other request waited behind them. Only a few run at a time; a symbol
            // that cannot get a turn is queued for a background download, and the caller reports
            // its history as still loading. A request never waits for a turn: waiting up to 45 s
            // with its connection held starved the pool after every market close.
            if (waitForTurn) {
                historyBackfillGate.acquire();
            } else if (!historyBackfillGate.tryAcquire()) {
                log.info("History top-up for {} deferred — other symbols are still loading; queued in the background", symbol);
                queueBackfill(symbol);
                return Freshness.DEFERRED;
            }
            try {
                // One writer per symbol. Concurrent requests for the same symbol (every holding's
                // analysis reads the Nifty) each saw the bar missing and each inserted it, which left
                // tens of thousands of duplicate bars. The second caller now waits, re-checks, and
                // finds the work already done.
                synchronized (lockFor(symbol)) {
                    PriceHistory again = newestDaily(names);
                    if (again != null && !again.getDate().isBefore(expected)) return Freshness.CURRENT;
                    storeWithRescaleCheck(symbol, range);
                    PriceHistory after = newestDaily(names);
                    boolean progressed = after != null && (again == null || after.getDate().isAfter(again.getDate()));
                    recordAttempt(symbol, expected, progressed, after);
                    return progressed ? Freshness.FETCHED : Freshness.NOTHING_NEW;
                }
            } finally {
                historyBackfillGate.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Freshness.FAILED;
        } catch (Exception e) {
            log.warn("Could not top up price history for {}: {}", symbol, e.getMessage());
            return Freshness.FAILED;
        }
    }

    /** How many symbols may download and store history at once; see {@link #freshen}. */
    private final java.util.concurrent.Semaphore historyBackfillGate = new java.util.concurrent.Semaphore(3);

    /** Per-symbol monitors shared by every history writer; one entry per symbol ever written. */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> historyLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private Object lockFor(String symbol) {
        return historyLocks.computeIfAbsent(symbol, k -> new Object());
    }

    /** Downloads deferred by a busy gate, run as slots free up rather than waiting for the next request. */
    private final java.util.concurrent.ExecutorService backfillPool = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "history-backfill");
        t.setDaemon(true);
        return t;
    });
    private final java.util.Set<String> queuedBackfills = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void queueBackfill(String symbol) {
        if (!queuedBackfills.add(symbol)) return;
        try {
            backfillPool.execute(() -> {
                try { freshen(symbol, true); } finally { queuedBackfills.remove(symbol); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            queuedBackfills.remove(symbol);
        }
    }

    @jakarta.annotation.PreDestroy
    void stopBackfills() {
        backfillPool.shutdownNow();
    }

    /**
     * A fetch that brought nothing newer for a session. On an exchange holiday, and for a
     * suspended or delisted symbol, the newest bar stays behind the expected session, and every
     * request used to ask Yahoo again — slow badges, rate-limit risk, and the download slots
     * taken from symbols that do have data.
     *
     * @param sessions distinct expected sessions in a row whose fetch found nothing
     * @param inactive found nothing for long enough to treat the symbol as no longer trading
     */
    private record EmptyAttempt(LocalDate expected, LocalDateTime at, int sessions, boolean inactive) {}

    private final java.util.concurrent.ConcurrentHashMap<String, EmptyAttempt> emptyAttempts = new java.util.concurrent.ConcurrentHashMap<>();

    /** A session whose fetch found nothing is asked about again only after this many hours. */
    static final int EMPTY_RETRY_HOURS = 3;
    /**
     * Empty sessions in a row, and how far behind the stored history must be, before a symbol
     * is marked inactive. Both, so that a provider outage of a few days doesn't mark every
     * holding as delisted. An inactive symbol is still asked about once a day.
     */
    static final int INACTIVE_AFTER_EMPTY_SESSIONS = 5;
    static final int INACTIVE_MIN_SESSIONS_BEHIND = 10;

    private boolean recentlyFoundNothing(String symbol, LocalDate expected) {
        EmptyAttempt a = emptyAttempts.get(symbol);
        if (a == null) return false;
        LocalDateTime now = LocalDateTime.now(IST);
        if (a.inactive()) return a.at().isAfter(now.minusHours(24));
        return a.expected().equals(expected) && a.at().isAfter(now.minusHours(EMPTY_RETRY_HOURS));
    }

    private void recordAttempt(String symbol, LocalDate expected, boolean progressed, PriceHistory newest) {
        if (progressed) {
            emptyAttempts.remove(symbol);
            setActive(symbol, true);
            return;
        }
        EmptyAttempt prev = emptyAttempts.get(symbol);
        int sessions = prev == null ? 1 : prev.expected().equals(expected) ? prev.sessions() : prev.sessions() + 1;
        int behind = newest == null ? Integer.MAX_VALUE : weekdaysBetween(newest.getDate(), expected);
        boolean inactive = sessions >= INACTIVE_AFTER_EMPTY_SESSIONS && behind >= INACTIVE_MIN_SESSIONS_BEHIND;
        emptyAttempts.put(symbol, new EmptyAttempt(expected, LocalDateTime.now(IST), sessions, inactive));
        if (inactive) setActive(symbol, false);
    }

    private void setActive(String symbol, boolean active) {
        try {
            stockRepository.findBySymbol(symbol).ifPresent(st -> {
                if (st.isActive() == active) return;
                st.setActive(active);
                stockRepository.save(st);
                if (active) log.info("{} is trading again — marked active", symbol);
                else log.warn("{} has had no new prices for {} sessions — marked inactive (possibly suspended or delisted)",
                        symbol, INACTIVE_AFTER_EMPTY_SESSIONS);
            });
        } catch (Exception e) {
            log.debug("Could not update active flag for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * True when the symbol has stopped getting new prices — repeated empty fetches, or a stock
     * row already marked inactive. Lets a stale result say "possibly suspended or delisted"
     * rather than "could not be refreshed".
     */
    public boolean isInactive(String symbol) {
        String s = canonicalSymbol(symbol);
        if (s == null) return false;
        EmptyAttempt a = emptyAttempts.get(s);
        if (a != null && a.inactive()) return true;
        try {
            return stockRepository.findBySymbol(s).map(st -> !st.isActive()).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Download and store {@code range} of daily history. Every writer comes through here or
     * {@link #freshen}: the symbol is canonicalised and the per-symbol lock taken, so the nightly
     * index refresh, the manual fetch endpoint and request-time top-ups can no longer each insert
     * the same bar. Deliberately not one transaction: the lock must outlive the write, or a
     * second writer could read before the first commits.
     */
    public void fetchAndStorePriceHistory(String symbol, String range) {
        String s = canonicalSymbol(symbol);
        synchronized (lockFor(s)) {
            storeWithRescaleCheck(s, range);
        }
    }

    private void storeWithRescaleCheck(String symbol, String range) {
        int rescaled = storeHistory(symbol, range);
        // A split or bonus inside a short top-up window means everything older than that
        // window is still on the old scale. Re-pull enough to cover every indicator window.
        if (rescaled > 0 && java.util.Set.of("1d", "5d", "1mo", "3mo", "6mo").contains(range)) {
            log.warn("{}: price scale changed — re-fetching 2y of history", symbol);
            storeHistory(symbol, "2y");
        }
    }

    /**
     * Set once the unique daily-bar index exists (see {@link PriceHistoryIntegrity}). New bars are
     * then upserted, so even a writer this JVM's lock can't see — a second instance — cannot add
     * a duplicate. Until then they are inserted as before, behind the lock.
     */
    private volatile boolean dailyKeyUnique;

    // Public: this bean is proxied, and the call must reach the target rather than set a field on the proxy.
    public void setDailyKeyUnique(boolean unique) {
        this.dailyKeyUnique = unique;
    }

    /** @return how many stored bars had to be rescaled (a split or bonus since they were stored). */
    private int storeHistory(String symbol, String range) {
        String yahooSymbol = toYahooSymbol(symbol);
        // toYahooSymbol() defaults an unsuffixed symbol to ".NS" — wrong for BSE-only stocks
        // (e.g. ones found via live Yahoo search), which produces an invalid ticker. Correct
        // it using the stock's recorded exchange when we have one.
        if (yahooSymbol.equals(symbol + ".NS")) {
            Stock stock = stockRepository.findBySymbol(symbol.toUpperCase()).orElse(null);
            if (stock != null && "BSE".equalsIgnoreCase(stock.getExchange())) yahooSymbol = symbol + ".BO";
        }
        // A bar for a session still in progress is a mid-day snapshot, not a close. Storing it
        // made the partial bar (half a day's volume, a mid-session "close") permanent.
        LocalDate lastClosed = lastCompletedSession(java.time.ZonedDateTime.now(IST));
        List<YahooFinanceClient.OhlcvBar> bars = yahooClient.getHistory(yahooSymbol, range, "1d").stream()
                .filter(bar -> !bar.date().isAfter(lastClosed))
                .toList();
        if (bars.isEmpty()) {
            log.info("No settled bars returned for {} ({})", symbol, range);
            return 0;
        }

        // One select for the rows we already have and one batched save. Bars already stored
        // are updated in place rather than skipped: Yahoo's history is split-adjusted, so after
        // a split or bonus (common in India) the stored pre-split prices would otherwise sit
        // next to post-split ones and read as a crash. Rewriting the fetched range keeps the
        // series consistent, and also corrects any earlier partial bar. Where a date is stored
        // more than once — or under the older ".NS" name — the copy readers keep is the one
        // rewritten, which also makes it the newest copy from then on.
        LocalDate from = bars.stream().map(YahooFinanceClient.OhlcvBar::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = bars.stream().map(YahooFinanceClient.OhlcvBar::date).max(LocalDate::compareTo).orElseThrow();
        Map<LocalDate, PriceHistory> existing = new HashMap<>();
        for (PriceHistory p : com.marketai.market.quality.PriceSeriesValidator.latestPerDate(
                priceHistoryRepository.findDailyBySymbolsAndDateBetween(storedNames(symbol), from, to))) {
            existing.put(p.getDate(), p);
        }

        java.time.LocalDateTime now = java.time.LocalDateTime.now(IST);
        List<PriceHistory> toSave = new ArrayList<>();
        int inserted = 0, updated = 0, rescaled = 0;
        for (YahooFinanceClient.OhlcvBar bar : bars) {
            BigDecimal open = scale2(bar.open()), high = scale2(bar.high()), low = scale2(bar.low());
            BigDecimal close = scale2(bar.close()), adj = scale2(bar.adjClose());
            PriceHistory row = existing.get(bar.date());
            if (row == null) {
                toSave.add(PriceHistory.builder().symbol(symbol).date(bar.date())
                        .open(open).high(high).low(low).close(close).adjClose(adj).volume(bar.volume())
                        .exchange(exchangeOf(yahooSymbol)).provider(PROVIDER)
                        .ingestedAt(now).updatedAt(now).build());
                inserted++;
                continue;
            }
            boolean changed = !eq(row.getOpen(), open) || !eq(row.getHigh(), high) || !eq(row.getLow(), low)
                    || !eq(row.getClose(), close) || !eq(row.getAdjClose(), adj)
                    || row.getVolume() == null || row.getVolume() != bar.volume();
            if (!changed) continue;
            if (row.getClose() != null && row.getClose().signum() > 0
                    && Math.abs(row.getClose().doubleValue() / bar.close() - 1) > 0.2) rescaled++;
            row.setOpen(open); row.setHigh(high); row.setLow(low); row.setClose(close);
            row.setAdjClose(adj); row.setVolume(bar.volume());
            row.setUpdatedAt(now);
            if (row.getProvider() == null) row.setProvider(PROVIDER);
            if (row.getExchange() == null) row.setExchange(exchangeOf(yahooSymbol));
            if (row.getInterval() == null) row.setInterval("1d");
            toSave.add(row);
            updated++;
        }
        // New rows go in as one JDBC batch. The ids are database identities, which stops
        // Hibernate from batching, so saveAll sent a separate round trip per row — ~2,500 of them
        // for a first 10-year fetch, minutes per symbol against a remote database.
        List<PriceHistory> fresh = toSave.stream().filter(r -> r.getId() == null).toList();
        if (jdbc != null && !fresh.isEmpty()) {
            jdbc.batchUpdate(dailyKeyUnique ? UPSERT_DAILY_BAR : INSERT_DAILY_BAR,
                fresh, 500, (ps, r) -> {
                    ps.setString(1, r.getSymbol());
                    ps.setObject(2, r.getDate());
                    ps.setBigDecimal(3, r.getOpen());
                    ps.setBigDecimal(4, r.getHigh());
                    ps.setBigDecimal(5, r.getLow());
                    ps.setBigDecimal(6, r.getClose());
                    ps.setBigDecimal(7, r.getAdjClose());
                    ps.setObject(8, r.getVolume());
                    ps.setString(9, r.getExchange());
                    ps.setString(10, r.getProvider());
                    ps.setString(11, r.getInterval());
                    ps.setObject(12, r.getIngestedAt());
                    ps.setObject(13, r.getUpdatedAt());
                });
            priceHistoryRepository.saveAll(toSave.stream().filter(r -> r.getId() != null).toList());
        } else {
            priceHistoryRepository.saveAll(toSave);
        }

        if (rescaled > 0) {
            log.warn("{}: {} stored bar(s) differed from Yahoo by more than 20% and were rewritten — likely a split or bonus", symbol, rescaled);
        }
        log.info("Price history for {}: {} fetched, {} new, {} corrected", symbol, bars.size(), inserted, updated);
        return rescaled;
    }

    private static final String INSERT_DAILY_BAR = "insert into price_history (symbol, date, open, high, low, close, adj_close, volume, "
            + "exchange, provider, bar_interval, ingested_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)";

    /** The insert above, made safe against a bar another writer stored first: that bar is rewritten instead. */
    static final String UPSERT_DAILY_BAR = INSERT_DAILY_BAR
            + " on conflict (symbol, date) where bar_interval = '1d' do update set open = excluded.open, high = excluded.high,"
            + " low = excluded.low, close = excluded.close, adj_close = excluded.adj_close, volume = excluded.volume,"
            + " provider = excluded.provider, updated_at = excluded.updated_at";

    static final String PROVIDER = "yahoo-chart";

    static String exchangeOf(String yahooSymbol) {
        if (yahooSymbol.startsWith("^")) return "INDEX";
        if (yahooSymbol.endsWith("=X")) return "FX";
        if (yahooSymbol.endsWith("=F")) return "FUTURES";
        return yahooSymbol.endsWith(".BO") ? "BSE" : "NSE";
    }

    private static BigDecimal scale2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean eq(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
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
        refreshTrackedSymbolHistory();
    }

    /**
     * Top up the daily history of every symbol analysed in the last 90 days. Only sector
     * indices used to be refreshed, so holdings and watchlist stocks kept whatever history was
     * fetched the first time they were looked at.
     *
     * The technicals cache is cleared explicitly at the end: this runs as a call from
     * {@link #scheduledSectorIndexHistoryRefresh} inside the same bean, which skips the proxy, so
     * a {@code @CacheEvict} here never fired and the night's new bars waited up to 15 minutes.
     */
    public void refreshTrackedSymbolHistory() {
        java.util.Set<String> symbols = new java.util.LinkedHashSet<>();
        for (String s : priceHistoryRepository.findDailySymbolsSince(LocalDate.now().minusDays(90))) {
            symbols.add(canonicalSymbol(s)); // legacy "X.NS" rows and new "X" rows are one symbol
        }
        for (String s : symbols) {
            if (s.startsWith("^") || s.endsWith(".MF")) continue; // indices above; funds have no feed
            ensureFreshDailyHistory(s);
        }
        evictTechnicals();
        log.info("Tracked-symbol history top-up checked {} symbol(s)", symbols.size());
    }

    /** Optional so the service works without a cache manager, as in unit tests. */
    private org.springframework.cache.CacheManager cacheManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCacheManager(org.springframework.cache.CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    private void evictTechnicals() {
        if (cacheManager == null) return;
        org.springframework.cache.Cache c = cacheManager.getCache("technicals");
        if (c != null) c.clear();
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

        // One bar per date: with a duplicated history a stale pre-split copy could come out as
        // the first or last close and give a wrong return.
        List<PriceHistory> niftyBars = com.marketai.market.quality.PriceSeriesValidator.latestPerDate(
                priceHistoryRepository.findDailyBySymbolsAndDateBetween(List.of(NIFTY_SYMBOL), from, to));
        BigDecimal niftyReturn = returnPercentOrNull(niftyBars);

        for (Map.Entry<String, String> entry : SECTOR_SYMBOLS.entrySet()) {
            String sector = entry.getKey();
            String symbol = entry.getValue();

            List<PriceHistory> bars = com.marketai.market.quality.PriceSeriesValidator.latestPerDate(
                    priceHistoryRepository.findDailyBySymbolsAndDateBetween(List.of(symbol), from, to));
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
