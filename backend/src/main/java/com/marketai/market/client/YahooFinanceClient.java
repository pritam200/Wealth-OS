package com.marketai.market.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketai.common.exception.ExternalApiException;
import com.marketai.market.dto.QuoteDto;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class YahooFinanceClient {

    private final WebClient.Builder webClientBuilder;

    /**
     * Since ~2024 Yahoo requires a session cookie + "crumb" token on quoteSummary (the
     * fundamentals endpoint) — an unauthenticated request gets a bare 401/429 with no data,
     * which previously looked identical to "Yahoo has no fundamentals for this symbol". The
     * chart endpoint (quotes/history) does not require this.
     *
     * Cached for CRUMB_TTL and refreshed lazily rather than per-request, since the
     * cookie+crumb handshake itself counts against the same rate limit it's meant to get us
     * past.
     */
    private static final java.time.Duration CRUMB_TTL = java.time.Duration.ofMinutes(50);
    private final AtomicReference<CrumbSession> crumbCache = new AtomicReference<>();

    private static final class CrumbSession {
        final String cookie;
        final String crumb;
        final Instant fetchedAt;
        CrumbSession(String cookie, String crumb, Instant fetchedAt) {
            this.cookie = cookie; this.crumb = crumb; this.fetchedAt = fetchedAt;
        }
        boolean isFresh() { return Instant.now().isBefore(fetchedAt.plus(CRUMB_TTL)); }
    }

    @Value("${app.yahoo-finance.base-url}")
    private String baseUrl;

    @Value("${app.yahoo-finance.timeout-seconds}")
    private int timeoutSeconds;

    /**
     * Fetch current quote for a symbol.
     * NSE stocks: append ".NS", BSE stocks: append ".BO"
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public QuoteDto getQuote(String yahooSymbol) {
        try {
            JsonNode root = webClientBuilder.build()
                    .get()
                    .uri(baseUrl + "/v8/finance/chart/{symbol}?interval=1d&range=1d", yahooSymbol)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            return parseQuote(root, yahooSymbol);
        } catch (WebClientResponseException e) {
            log.warn("Yahoo Finance API error for {}: {} {}", yahooSymbol, e.getStatusCode(), e.getMessage());
            throw new ExternalApiException("YahooFinance", "Failed to fetch quote for " + yahooSymbol);
        }
    }

    /**
     * Fetch OHLCV history for a symbol.
     */
    /**
     * P/E, market cap and sector — NOT returned by the chart endpoint used for quotes/history;
     * these live in Yahoo's separate quoteSummary API. Fetched once per stock and cached on
     * the Stock row by MarketDataService (that's why these fields render "—" until populated).
     *
     * Also pulls the `financialData` and `defaultKeyStatistics` modules for the real
     * fundamentals (ROE, D/E, growth, margins, P/B, EPS). Yahoo omits any of these for a
     * given symbol at will — coverage for Indian equities is patchy — so EVERY field here
     * is nullable and is left null rather than defaulted. Never substitute an estimate.
     */
    public Fundamentals getFundamentals(String yahooSymbol) {
        CrumbSession session = getOrRefreshCrumb();
        if (session == null) {
            // Distinct from "Yahoo has no fundamentals for this symbol" — we never even got
            // to ask. Logged at WARN (not DEBUG) so this is diagnosable in production instead
            // of looking identical to sparse per-symbol coverage.
            log.warn("Yahoo fundamentals SKIPPED for {} — could not obtain a session cookie/crumb (likely rate-limited).", yahooSymbol);
            return null;
        }
        try {
            JsonNode root = webClientBuilder.build()
                    .get()
                    .uri("https://query2.finance.yahoo.com/v10/finance/quoteSummary/{symbol}?modules=summaryDetail,assetProfile,financialData,defaultKeyStatistics&crumb={crumb}",
                            yahooSymbol, session.crumb)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Cookie", session.cookie)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            JsonNode result = root != null ? root.at("/quoteSummary/result/0") : null;
            if (result == null || result.isMissingNode()) return null;

            BigDecimal pe = numOrNull(result.at("/summaryDetail/trailingPE"));
            BigDecimal marketCap = numOrNull(result.at("/summaryDetail/marketCap"));
            String sector = result.at("/assetProfile/sector").isMissingNode() ? null : result.at("/assetProfile/sector").asText(null);

            // Fractions (0.184 = 18.4%) keep 6 dp — rounding them to 2 dp would throw away
            // most of the signal. Ratios/absolutes stay at the existing 2 dp.
            BigDecimal roe = numOrNull(result.at("/financialData/returnOnEquity"), FRACTION_SCALE);
            if (roe == null) {
                // financialData omits ROE for a fair number of Indian names; defaultKeyStatistics
                // sometimes still carries it. Fallback only — never a computed guess.
                roe = numOrNull(result.at("/defaultKeyStatistics/returnOnEquity"), FRACTION_SCALE);
            }
            BigDecimal debtToEquity = numOrNull(result.at("/financialData/debtToEquity"));
            BigDecimal revenueGrowth = numOrNull(result.at("/financialData/revenueGrowth"), FRACTION_SCALE);
            BigDecimal earningsGrowth = numOrNull(result.at("/financialData/earningsGrowth"), FRACTION_SCALE);
            BigDecimal profitMargin = numOrNull(result.at("/financialData/profitMargins"), FRACTION_SCALE);
            BigDecimal currentRatio = numOrNull(result.at("/financialData/currentRatio"));
            BigDecimal pb = numOrNull(result.at("/defaultKeyStatistics/priceToBook"));
            BigDecimal eps = numOrNull(result.at("/defaultKeyStatistics/trailingEps"));

            return Fundamentals.builder()
                    .pe(pe)
                    .marketCap(marketCap)
                    .sector(sector)
                    .roe(roe)
                    .debtToEquity(debtToEquity)
                    .revenueGrowth(revenueGrowth)
                    .earningsGrowth(earningsGrowth)
                    .profitMargin(profitMargin)
                    .currentRatio(currentRatio)
                    .pb(pb)
                    .eps(eps)
                    .build();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429 || e.getStatusCode().value() == 401) {
                log.warn("Yahoo fundamentals RATE-LIMITED/UNAUTHORIZED for {}: {} — invalidating cached crumb.", yahooSymbol, e.getStatusCode());
                crumbCache.set(null); // stale/rejected — force a fresh handshake next call
            } else {
                log.debug("Yahoo fundamentals fetch failed for {}: {} {}", yahooSymbol, e.getStatusCode(), e.getMessage());
            }
            return null;
        } catch (Exception e) {
            log.debug("Yahoo fundamentals fetch failed for {}: {}", yahooSymbol, e.getMessage());
            return null;
        }
    }

    /**
     * Two-step handshake: GET a Yahoo domain to receive the session cookie (Set-Cookie),
     * then GET the crumb endpoint presenting that cookie. Both steps and the resulting crumb
     * are cached together — a crumb is only valid alongside the cookie it was issued for.
     */
    private synchronized CrumbSession getOrRefreshCrumb() {
        CrumbSession cached = crumbCache.get();
        if (cached != null && cached.isFresh()) return cached;

        try {
            WebClient client = webClientBuilder.build();

            List<String> setCookies = client.get()
                    .uri("https://fc.yahoo.com")
                    .header("User-Agent", USER_AGENT)
                    .exchangeToMono(resp -> resp.releaseBody()
                            .thenReturn(resp.headers().header("Set-Cookie")))
                    .block();
            if (setCookies == null || setCookies.isEmpty()) {
                log.warn("Yahoo crumb handshake: no Set-Cookie header received — cannot proceed.");
                return null;
            }
            // Each Set-Cookie value is "name=value; attr; attr…" — keep just "name=value".
            StringBuilder cookieHeader = new StringBuilder();
            for (String sc : setCookies) {
                String pair = sc.split(";", 2)[0].trim();
                if (cookieHeader.length() > 0) cookieHeader.append("; ");
                cookieHeader.append(pair);
            }
            String cookie = cookieHeader.toString();

            String crumb = client.get()
                    .uri("https://query2.finance.yahoo.com/v1/test/getcrumb")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", cookie)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (crumb == null || crumb.trim().isEmpty() || crumb.contains("Too Many Requests") || crumb.contains("<html")) {
                log.warn("Yahoo crumb handshake failed — got [{}] instead of a token (likely rate-limited).",
                        crumb != null ? crumb.substring(0, Math.min(40, crumb.length())) : "null");
                return null;
            }

            CrumbSession session = new CrumbSession(cookie, URLEncoder.encode(crumb.trim(), StandardCharsets.UTF_8.name()), Instant.now());
            crumbCache.set(session);
            log.info("Yahoo crumb handshake succeeded — cached for {} min.", CRUMB_TTL.toMinutes());
            return session;
        } catch (Exception e) {
            log.warn("Yahoo crumb handshake error: {}", e.getMessage());
            return null;
        }
    }

    /** Scale for fields Yahoo reports as a fraction of 1 (ROE, growth rates, margins). */
    private static final int FRACTION_SCALE = 6;

    private BigDecimal numOrNull(JsonNode node) {
        return numOrNull(node, 2);
    }

    private BigDecimal numOrNull(JsonNode node, int scale) {
        JsonNode raw = node.at("/raw");
        return raw.isMissingNode() || raw.isNull() ? null : BigDecimal.valueOf(raw.asDouble()).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Every field is nullable by design: Yahoo simply does not return some of these for many
     * Indian tickers. A null here means "unavailable", never "zero".
     */
    @Getter
    @Builder
    @Accessors(fluent = true)
    public static class Fundamentals {
        private final BigDecimal pe;
        private final BigDecimal marketCap;
        private final String sector;
        private final BigDecimal roe;              // fraction, e.g. 0.184 = 18.4%
        private final BigDecimal debtToEquity;
        private final BigDecimal revenueGrowth;    // fraction
        private final BigDecimal earningsGrowth;   // fraction
        private final BigDecimal profitMargin;     // fraction
        private final BigDecimal currentRatio;
        private final BigDecimal pb;
        private final BigDecimal eps;
    }

    /**
     * Live symbol search against Yahoo's own autocomplete endpoint — used as a fallback
     * when the local stock master (a curated ~120-name seed list) has no match, so any
     * real NSE/BSE symbol is searchable, not just the popular names we bothered to seed.
     */
    public List<SymbolHit> searchSymbols(String query) {
        List<SymbolHit> out = new ArrayList<>();
        try {
            JsonNode root = webClientBuilder.build()
                    .get()
                    .uri("https://query2.finance.yahoo.com/v1/finance/search?q={q}&quotesCount=10&newsCount=0", query)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            JsonNode quotes = root != null ? root.at("/quotes") : null;
            if (quotes != null && quotes.isArray()) {
                for (JsonNode q : quotes) {
                    String symbol = q.at("/symbol").asText(null);
                    if (symbol == null || !(symbol.endsWith(".NS") || symbol.endsWith(".BO"))) continue; // Indian equities only
                    String name = q.at("/shortname").isMissingNode() ? q.at("/longname").asText(symbol) : q.at("/shortname").asText(symbol);
                    out.add(new SymbolHit(symbol.replace(".NS", "").replace(".BO", ""), name, symbol.endsWith(".NS") ? "NSE" : "BSE"));
                }
            }
        } catch (Exception e) {
            log.debug("Yahoo symbol search failed for '{}': {}", query, e.getMessage());
        }
        return out;
    }

    public static class SymbolHit {
        private final String symbol;
        private final String name;
        private final String exchange;
        public SymbolHit(String symbol, String name, String exchange) { this.symbol = symbol; this.name = name; this.exchange = exchange; }
        public String symbol() { return symbol; }
        public String name() { return name; }
        public String exchange() { return exchange; }
    }

    public List<OhlcvBar> getHistory(String yahooSymbol, String range, String interval) {
        try {
            JsonNode root = webClientBuilder.build()
                    .get()
                    .uri(baseUrl + "/v8/finance/chart/{symbol}?interval={interval}&range={range}",
                            yahooSymbol, interval, range)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            return parseHistory(root);
        } catch (WebClientResponseException e) {
            log.warn("Yahoo history API error for {} ({}): {}", yahooSymbol, range, e.getStatusCode());
            return new ArrayList<>();
        } catch (Exception e) {
            log.warn("Yahoo history fetch failed for {}: {}", yahooSymbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    private QuoteDto parseQuote(JsonNode root, String symbol) {
        JsonNode result = root.at("/chart/result/0");
        JsonNode meta = result.at("/meta");
        JsonNode quote = result.at("/indicators/quote/0");

        BigDecimal currentPrice = bd(meta.at("/regularMarketPrice").asDouble());
        BigDecimal previousClose = bd(meta.at("/chartPreviousClose").asDouble());
        BigDecimal change = currentPrice.subtract(previousClose).setScale(2, RoundingMode.HALF_UP);
        BigDecimal changePct = previousClose.compareTo(BigDecimal.ZERO) != 0
                ? change.divide(previousClose, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return QuoteDto.builder()
                .symbol(symbol)
                .currentPrice(currentPrice)
                .previousClose(previousClose)
                .open(bd(meta.at("/regularMarketOpen").asDouble()))
                .high(bd(meta.at("/regularMarketDayHigh").asDouble()))
                .low(bd(meta.at("/regularMarketDayLow").asDouble()))
                .volume(meta.at("/regularMarketVolume").asLong())
                .change(change)
                .changePercent(changePct)
                .weekHigh52(bd(meta.at("/fiftyTwoWeekHigh").asDouble()))
                .weekLow52(bd(meta.at("/fiftyTwoWeekLow").asDouble()))
                .build();
    }

    private List<OhlcvBar> parseHistory(JsonNode root) {
        List<OhlcvBar> bars = new ArrayList<>();
        JsonNode result = root.at("/chart/result/0");
        JsonNode timestamps = result.at("/timestamp");
        JsonNode ohlcv = result.at("/indicators/quote/0");

        if (!timestamps.isArray()) return bars;

        for (int i = 0; i < timestamps.size(); i++) {
            long ts = timestamps.get(i).asLong();
            LocalDate date = java.time.Instant.ofEpochSecond(ts)
                    .atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();

            bars.add(new OhlcvBar(
                    date,
                    safeDouble(ohlcv.at("/open/" + i)),
                    safeDouble(ohlcv.at("/high/" + i)),
                    safeDouble(ohlcv.at("/low/" + i)),
                    safeDouble(ohlcv.at("/close/" + i)),
                    ohlcv.at("/volume/" + i).asLong(0)
            ));
        }
        return bars;
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private double safeDouble(JsonNode node) {
        return node.isNull() ? 0.0 : node.asDouble();
    }

    public static class OhlcvBar {
        private final LocalDate date;
        private final double open;
        private final double high;
        private final double low;
        private final double close;
        private final long volume;

        public OhlcvBar(LocalDate date, double open, double high, double low, double close, long volume) {
            this.date = date;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }

        public LocalDate date() { return date; }
        public double open() { return open; }
        public double high() { return high; }
        public double low() { return low; }
        public double close() { return close; }
        public long volume() { return volume; }
    }
}
