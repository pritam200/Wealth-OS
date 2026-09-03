package com.marketai.recommendation.service;

import com.marketai.market.entity.Stock;
import com.marketai.market.repository.StockRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.tracking.dto.TrackingSummaryResponse;
import com.marketai.tracking.service.TrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the whole-portfolio picture that per-security analysis needs in order to answer
 * "should I buy more of this?" rather than just "is this a good stock?".
 *
 * Concentration thresholds below are conventional portfolio-construction limits, stated
 * explicitly so a user can see why something was flagged:
 *   - a single stock above 10% of total assets, or 25% of the equity book, is a
 *     position-size risk regardless of how good the stock looks;
 *   - a single sector above 30% of the (known-sector) equity book is a correlation risk;
 *   - an equity share above 85% of assets is an aggressive allocation worth naming.
 * These are heuristics for surfacing risk, not predictions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioContextService {

    private static final double SINGLE_STOCK_PCT_OF_ASSETS_HIGH = 10.0;
    private static final double SINGLE_STOCK_PCT_OF_EQUITY_HIGH = 25.0;
    private static final double SECTOR_PCT_OF_EQUITY_HIGH = 30.0;
    private static final double EQUITY_PCT_AGGRESSIVE = 85.0;
    private static final double SECTOR_COVERAGE_TRUSTWORTHY = 80.0;

    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final StockRepository stockRepository;
    private final TrackingService trackingService;

    /**
     * Every holding across EVERY portfolio the user owns. A user can accumulate several
     * portfolio rows (each Gmail-import path used to resolve "the portfolio" independently),
     * and looking at only one silently hides real holdings — this is the one place that
     * aggregation happens so every caller (this service, Today's Actions, anything else)
     * sees the same complete set.
     */
    @Transactional(readOnly = true)
    public List<Holding> getAllHoldings(Long userId) {
        List<Holding> holdings = new ArrayList<>();
        for (Portfolio p : portfolioRepository.findByUserIdOrderByIdAsc(userId)) {
            holdings.addAll(holdingRepository.findByPortfolioId(p.getId()));
        }
        return holdings;
    }

    @Transactional(readOnly = true)
    public PortfolioContext build(Long userId) {
        List<String> gaps = new ArrayList<>();
        List<Holding> holdings = getAllHoldings(userId);

        BigDecimal stocksValue = BigDecimal.ZERO, mfValue = BigDecimal.ZERO;
        BigDecimal equityInvested = BigDecimal.ZERO, equityCurrent = BigDecimal.ZERO;
        int stockCount = 0, mfCount = 0;

        List<Holding> stocks = new ArrayList<>();
        for (Holding h : holdings) {
            BigDecimal cur = nz(h.getCurrentValue());
            BigDecimal inv = nz(h.getInvestedValue());
            equityInvested = equityInvested.add(inv);
            equityCurrent = equityCurrent.add(cur);
            if (isMf(h)) {
                mfValue = mfValue.add(cur);
                mfCount++;
            } else {
                stocksValue = stocksValue.add(cur);
                stocks.add(h);
                stockCount++;
            }
        }

        TrackingSummaryResponse t = null;
        try {
            t = trackingService.getSummary(userId);
        } catch (Exception e) {
            log.warn("Tracking summary unavailable for user {}: {}", userId, e.getMessage());
            gaps.add("Fixed deposits, RDs, EPF and other assets could not be loaded — asset-allocation percentages below cover only stocks and mutual funds.");
        }

        BigDecimal fd    = t != null ? firstNonNull(t.getTotalFdCurrentValue(), t.getTotalFdPrincipal()) : BigDecimal.ZERO;
        BigDecimal rd    = t != null ? nz(t.getTotalRdCurrentValue()) : BigDecimal.ZERO;
        BigDecimal epf   = t != null ? nz(t.getTotalEpf()) : BigDecimal.ZERO;
        BigDecimal other = t != null ? nz(t.getTotalOtherAssets()) : BigDecimal.ZERO;
        BigDecimal loans = t != null ? nz(t.getTotalLoanOutstanding()) : BigDecimal.ZERO;

        BigDecimal totalAssets = stocksValue.add(mfValue).add(fd).add(rd).add(epf).add(other);
        BigDecimal netWorth = totalAssets.subtract(loans);
        BigDecimal equityValue = stocksValue.add(mfValue);

        Double equityPct = pct(equityValue, totalAssets);
        Double debtPct   = pct(fd.add(rd).add(epf), totalAssets);
        Double otherPct  = pct(other, totalAssets);

        BigDecimal equityPnl = equityCurrent.subtract(equityInvested);
        Double equityPnlPct = equityInvested.compareTo(BigDecimal.ZERO) > 0
            ? equityPnl.divide(equityInvested, 4, RoundingMode.HALF_UP).doubleValue() * 100 : null;

        /* ── Per-stock exposure ─────────────────────────────────── */
        List<PortfolioContext.Exposure> topStocks = new ArrayList<>();
        for (Holding h : stocks) {
            BigDecimal cur = nz(h.getCurrentValue());
            if (cur.compareTo(BigDecimal.ZERO) <= 0) continue;
            topStocks.add(PortfolioContext.Exposure.builder()
                .label(displayLabel(h))
                .value(cur)
                .percentOfTotalAssets(pct(cur, totalAssets))
                .percentOfEquity(pct(cur, equityValue))
                .holdingCount(1)
                .sector(sectorOf(h.getSymbol()))
                .build());
        }
        topStocks.sort(Comparator.comparing(
            (PortfolioContext.Exposure e) -> e.getValue(), Comparator.reverseOrder()));

        /* ── Sector exposure (direct stocks only) ───────────────── */
        Map<String, BigDecimal> bySector = new LinkedHashMap<>();
        Map<String, Integer> countBySector = new LinkedHashMap<>();
        BigDecimal knownSectorValue = BigDecimal.ZERO;
        for (PortfolioContext.Exposure e : topStocks) {
            if (e.getSector() == null || e.getSector().trim().isEmpty()) continue;
            String s = e.getSector().trim();
            bySector.merge(s, e.getValue(), BigDecimal::add);
            countBySector.merge(s, 1, Integer::sum);
            knownSectorValue = knownSectorValue.add(e.getValue());
        }

        List<PortfolioContext.Exposure> sectorExposures = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> en : bySector.entrySet()) {
            sectorExposures.add(PortfolioContext.Exposure.builder()
                .label(en.getKey())
                .value(en.getValue())
                .percentOfTotalAssets(pct(en.getValue(), totalAssets))
                // Percent of the KNOWN-sector stock book — not of all equity, which would
                // understate concentration whenever some sectors are unknown.
                .percentOfEquity(pct(en.getValue(), knownSectorValue))
                .holdingCount(countBySector.getOrDefault(en.getKey(), 0))
                .build());
        }
        sectorExposures.sort(Comparator.comparing(
            (PortfolioContext.Exposure e) -> e.getValue(), Comparator.reverseOrder()));

        Double sectorCoverage = pct(knownSectorValue, stocksValue);

        /* ── Flags ──────────────────────────────────────────────── */
        List<PortfolioContext.Flag> flags = new ArrayList<>();
        for (PortfolioContext.Exposure e : topStocks) {
            Double ofAssets = e.getPercentOfTotalAssets();
            Double ofEquity = e.getPercentOfEquity();
            if (ofAssets != null && ofAssets > SINGLE_STOCK_PCT_OF_ASSETS_HIGH) {
                flags.add(PortfolioContext.Flag.builder()
                    .type("SINGLE_STOCK").label(e.getLabel()).percent(round1(ofAssets)).severity("HIGH")
                    .message(String.format("%s is %.1f%% of your total assets — above the %.0f%% single-position guideline.",
                        e.getLabel(), ofAssets, SINGLE_STOCK_PCT_OF_ASSETS_HIGH))
                    .build());
            } else if (ofEquity != null && ofEquity > SINGLE_STOCK_PCT_OF_EQUITY_HIGH) {
                flags.add(PortfolioContext.Flag.builder()
                    .type("SINGLE_STOCK").label(e.getLabel()).percent(round1(ofEquity)).severity("HIGH")
                    .message(String.format("%s is %.1f%% of your equity book — above the %.0f%% guideline.",
                        e.getLabel(), ofEquity, SINGLE_STOCK_PCT_OF_EQUITY_HIGH))
                    .build());
            }
        }
        for (PortfolioContext.Exposure e : sectorExposures) {
            Double ofEquity = e.getPercentOfEquity();
            if (ofEquity != null && ofEquity > SECTOR_PCT_OF_EQUITY_HIGH) {
                flags.add(PortfolioContext.Flag.builder()
                    .type("SECTOR").label(e.getLabel()).percent(round1(ofEquity)).severity("HIGH")
                    .message(String.format("%.1f%% of your classified direct-equity is in %s (%d holding%s) — sector correlation risk.",
                        ofEquity, e.getLabel(), e.getHoldingCount(), e.getHoldingCount() == 1 ? "" : "s"))
                    .build());
            }
        }
        if (equityPct != null && equityPct > EQUITY_PCT_AGGRESSIVE) {
            flags.add(PortfolioContext.Flag.builder()
                .type("ASSET_ALLOCATION").label("Equity allocation").percent(round1(equityPct)).severity("MODERATE")
                .message(String.format("%.0f%% of assets are in equity — an aggressive allocation with little debt cushion.", equityPct))
                .build());
        }
        if (loans.compareTo(BigDecimal.ZERO) > 0 && totalAssets.compareTo(BigDecimal.ZERO) > 0) {
            Double leverage = pct(loans, totalAssets);
            if (leverage != null && leverage > 50) {
                flags.add(PortfolioContext.Flag.builder()
                    .type("LEVERAGE").label("Outstanding loans").percent(round1(leverage)).severity("MODERATE")
                    .message(String.format("Loans equal %.0f%% of total assets — factor repayment priority into any new investment.", leverage))
                    .build());
            }
        }

        /* ── Declared gaps ──────────────────────────────────────── */
        if (sectorCoverage != null && sectorCoverage < SECTOR_COVERAGE_TRUSTWORTHY) {
            gaps.add(String.format(
                "Sector is known for only %.0f%% of your direct-stock value, so sector concentration below is partial. It fills in as each symbol's profile is fetched.",
                sectorCoverage));
        }
        if (mfCount > 0) {
            // The single genuinely-blocked analysis: without per-fund constituent disclosures
            // there is no way to know a fund's underlying stocks, so true look-through overlap
            // between direct equity and funds cannot be computed. Say so rather than implying
            // the sector picture is complete.
            gaps.add(String.format(
                "Sector concentration covers direct stocks only. Your %d mutual fund holding%s cannot be looked through to underlying stocks — fund constituent data is not available, so true stock/fund overlap is not computed.",
                mfCount, mfCount == 1 ? "" : "s"));
        }

        String dataQuality = gaps.isEmpty() ? "FULL" : "PARTIAL";

        return PortfolioContext.builder()
            .stocksValue(scale(stocksValue)).mfValue(scale(mfValue))
            .fdValue(scale(fd)).rdValue(scale(rd)).epfValue(scale(epf))
            .otherAssetsValue(scale(other)).loansOutstanding(scale(loans))
            .totalAssets(scale(totalAssets)).netWorth(scale(netWorth))
            .equityPercent(round1(equityPct)).debtPercent(round1(debtPct)).otherPercent(round1(otherPct))
            .equityInvested(scale(equityInvested)).equityCurrent(scale(equityCurrent))
            .equityPnl(scale(equityPnl)).equityPnlPercent(round1(equityPnlPct))
            .stockCount(stockCount).mfCount(mfCount)
            .topStockExposures(topStocks.size() > 10 ? topStocks.subList(0, 10) : topStocks)
            .sectorExposures(sectorExposures)
            .concentrationFlags(flags)
            .sectorCoveragePercent(round1(sectorCoverage))
            .sectorScopeNote("Direct stocks only — mutual fund underlying holdings are not available.")
            .dataQuality(dataQuality)
            .dataGaps(gaps)
            .build();
    }

    public static boolean isMf(Holding h) {
        return h.getSymbol() != null && h.getSymbol().toUpperCase().endsWith(".MF");
    }

    private String displayLabel(Holding h) {
        if (h.getSymbol() != null) return h.getSymbol().replace(".NS", "").replace(".BO", "");
        return h.getName() != null ? h.getName() : "Unknown";
    }

    /** Sector as recorded on the Stock row; null when it hasn't been fetched yet. */
    private String sectorOf(String symbol) {
        if (symbol == null) return null;
        try {
            Optional<Stock> s = stockRepository.findBySymbol(symbol);
            return s.map(Stock::getSector).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private static BigDecimal firstNonNull(BigDecimal a, BigDecimal b) {
        if (a != null && a.compareTo(BigDecimal.ZERO) != 0) return a;
        return nz(b);
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** null (not 0) when the denominator is zero — an undefined share must not read as "none". */
    private static Double pct(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.compareTo(BigDecimal.ZERO) <= 0) return null;
        return part.divide(whole, 6, RoundingMode.HALF_UP).doubleValue() * 100;
    }

    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10) / 10.0;
    }
}
