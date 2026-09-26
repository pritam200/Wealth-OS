package com.marketai.rebalancing.service;

import com.marketai.market.entity.Stock;
import com.marketai.market.repository.StockRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import com.marketai.rebalancing.dto.AssetClassExposure;
import com.marketai.rebalancing.dto.RebalancingSuggestionsResponse;
import com.marketai.rebalancing.dto.TaxImpactDto;
import com.marketai.rebalancing.dto.TrimSuggestionDto;
import com.marketai.scoring.factor.FactorScore;
import com.marketai.scoring.factor.QualityFactor;
import com.marketai.tax.lot.CapitalGainsRates;
import com.marketai.tax.lot.DisposalCalculator;
import com.marketai.tax.lot.FyExemptionLedger;
import com.marketai.tax.lot.TaxLot;
import com.marketai.tracking.entity.OtherAsset;
import com.marketai.tracking.repository.OtherAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Read-only concentration-risk report built entirely from real holdings and, where a sell is
 * suggested, real tax-lot history — never an executed trade and never an invented number.
 *
 * <p>The competitor research this feature is scoped from ("Basket/allocation rebalancing
 * suggestions") explicitly warns that a wrong tax-aware sell suggestion is worse than none, and
 * separately that the tax-lot engine must be battle-tested first. This class therefore never
 * computes tax itself — every rupee of tax shown comes from {@link DisposalCalculator} against
 * this holding's own FIFO-replayed lots — and it never proposes a target allocation, because no
 * user-defined target exists anywhere in this codebase (checked: {@code FinancialGoal} stores
 * rupee targets and dates, never an asset mix). What it adds on top of the concentration flags
 * {@link PortfolioContextService} already computes is: which specific holding to trim (preferring
 * the lower-quality one within a flagged sector, per {@link QualityFactor}), how much, and what
 * that specific sale would actually cost in tax.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RebalancingService {

    private static final BigDecimal SINGLE_STOCK_PCT_OF_ASSETS_HIGH = new BigDecimal("10.0");
    private static final BigDecimal SINGLE_STOCK_PCT_OF_EQUITY_HIGH = new BigDecimal("25.0");
    private static final BigDecimal SECTOR_PCT_OF_EQUITY_HIGH = new BigDecimal("30.0");

    private final PortfolioContextService portfolioContextService;
    private final StockRepository stockRepository;
    private final TransactionRepository transactionRepository;
    private final OtherAssetRepository otherAssetRepository;
    private final DisposalCalculator disposalCalculator;
    private final QualityFactor qualityFactor;

    @Transactional(readOnly = true)
    public RebalancingSuggestionsResponse build(Long userId) {
        PortfolioContext ctx = portfolioContextService.build(userId);
        List<Holding> holdings = portfolioContextService.getAllHoldings(userId);

        List<String> gaps = new ArrayList<>(ctx.getDataGaps() != null ? ctx.getDataGaps() : List.of());

        List<AssetClassExposure> breakdown = assetClassBreakdown(userId, ctx);

        List<TrimSuggestionDto> suggestions = new ArrayList<>();
        if (ctx.getConcentrationFlags() != null) {
            for (PortfolioContext.Flag flag : ctx.getConcentrationFlags()) {
                try {
                    TrimSuggestionDto s = buildSuggestion(flag, ctx, holdings);
                    if (s != null) suggestions.add(s);
                } catch (Exception e) {
                    log.warn("Rebalancing: could not build a trim suggestion for flag {} ({}): {}",
                        flag.getType(), flag.getLabel(), e.getMessage());
                }
            }
        }

        if (!suggestions.isEmpty()) {
            gaps.add("Tax figures below assume no other long-term equity gains have already been "
                + "realised elsewhere this financial year and no income-tax surcharge applies — "
                + "neither is tracked here, so actual tax payable may be higher.");
        }

        return RebalancingSuggestionsResponse.builder()
            .generatedAt(LocalDateTime.now())
            .totalAssets(ctx.getTotalAssets())
            .assetClassBreakdown(breakdown)
            .sectorBreakdown(ctx.getSectorExposures())
            .concentrationFlags(ctx.getConcentrationFlags())
            .trimSuggestions(suggestions)
            .dataGaps(gaps)
            .scopeNote("Shows your current allocation and flags concentration against conventional "
                + "portfolio-construction guidelines — this is not a target-allocation rebalancer, "
                + "since no target allocation is defined anywhere in your account. Nothing here is "
                + "executed; every suggestion is a read-only idea with its real tax cost, never an "
                + "estimate.")
            .build();
    }

    private List<AssetClassExposure> assetClassBreakdown(Long userId, PortfolioContext ctx) {
        BigDecimal totalAssets = nz(ctx.getTotalAssets());
        BigDecimal debtValue = nz(ctx.getFdValue()).add(nz(ctx.getRdValue())).add(nz(ctx.getEpfValue()));
        BigDecimal goldValue = goldValue(userId);
        BigDecimal otherLessGold = nz(ctx.getOtherAssetsValue()).subtract(goldValue).max(BigDecimal.ZERO);

        List<AssetClassExposure> out = new ArrayList<>();
        addIfPositive(out, "Equity (direct stocks)", nz(ctx.getStocksValue()), totalAssets);
        addIfPositive(out, "Mutual funds", nz(ctx.getMfValue()), totalAssets);
        addIfPositive(out, "Debt (FD/RD/EPF)", debtValue, totalAssets);
        addIfPositive(out, "Gold", goldValue, totalAssets);
        addIfPositive(out, "Cash", nz(ctx.getCashValue()), totalAssets);
        addIfPositive(out, "Other", otherLessGold, totalAssets);
        out.sort(Comparator.comparing(AssetClassExposure::getValue, Comparator.reverseOrder()));
        return out;
    }

    private void addIfPositive(List<AssetClassExposure> out, String label, BigDecimal value, BigDecimal totalAssets) {
        if (value.signum() <= 0) return;
        out.add(AssetClassExposure.builder()
            .label(label).value(value.setScale(2, RoundingMode.HALF_UP))
            .percentOfTotalAssets(pct(value, totalAssets))
            .build());
    }

    /** Gold specifically, from {@code other_assets.category = 'gold'} — real recorded data, not an estimate. */
    private BigDecimal goldValue(Long userId) {
        return otherAssetRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .filter(a -> a.getCategory() != null && a.getCategory().trim().equalsIgnoreCase("gold"))
            .map(OtherAsset::getValue)
            .filter(v -> v != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private TrimSuggestionDto buildSuggestion(PortfolioContext.Flag flag, PortfolioContext ctx, List<Holding> holdings) {
        if ("SINGLE_STOCK".equals(flag.getType())) {
            return buildSingleStockSuggestion(flag, ctx, holdings);
        }
        if ("SECTOR".equals(flag.getType())) {
            return buildSectorSuggestion(flag, ctx, holdings);
        }
        return null; // ASSET_ALLOCATION / LEVERAGE aren't tied to one sellable holding
    }

    private TrimSuggestionDto buildSingleStockSuggestion(PortfolioContext.Flag flag, PortfolioContext ctx, List<Holding> holdings) {
        Holding h = findByDisplayLabel(flag.getLabel(), holdings);
        if (h == null || h.getCurrentPrice() == null || h.getCurrentPrice().signum() <= 0) return null;

        BigDecimal currentValue = h.getCurrentValue();
        BigDecimal totalAssets = nz(ctx.getTotalAssets());
        BigDecimal equityValue = nz(ctx.getStocksValue()).add(nz(ctx.getMfValue()));

        Double pctOfAssets = pct(currentValue, totalAssets);
        Double pctOfEquity = pct(currentValue, equityValue);

        BigDecimal target;
        String basis;
        Double currentPercent;
        if (pctOfAssets != null && BigDecimal.valueOf(pctOfAssets).compareTo(SINGLE_STOCK_PCT_OF_ASSETS_HIGH) > 0) {
            target = totalAssets.multiply(SINGLE_STOCK_PCT_OF_ASSETS_HIGH).divide(BigDecimal.valueOf(100));
            basis = "10% of total assets guideline";
            currentPercent = pctOfAssets;
        } else if (pctOfEquity != null && BigDecimal.valueOf(pctOfEquity).compareTo(SINGLE_STOCK_PCT_OF_EQUITY_HIGH) > 0) {
            target = equityValue.multiply(SINGLE_STOCK_PCT_OF_EQUITY_HIGH).divide(BigDecimal.valueOf(100));
            basis = "25% of equity-book guideline";
            currentPercent = pctOfEquity;
        } else {
            return null; // flag no longer reproducible against fresh numbers — don't guess
        }

        BigDecimal excessValue = currentValue.subtract(target).min(currentValue).max(BigDecimal.ZERO);
        if (excessValue.signum() <= 0) return null;

        return sizeAndPrice(flag.getType(), flag.getLabel(), h, excessValue, currentValue, currentPercent, basis,
            flag.getMessage(), "Only holding flagged.");
    }

    private TrimSuggestionDto buildSectorSuggestion(PortfolioContext.Flag flag, PortfolioContext ctx, List<Holding> holdings) {
        String sector = flag.getLabel();
        PortfolioContext.Exposure sectorExposure = ctx.getSectorExposures() == null ? null
            : ctx.getSectorExposures().stream().filter(e -> sector.equals(e.getLabel())).findFirst().orElse(null);
        if (sectorExposure == null || sectorExposure.getPercentOfEquity() == null
                || sectorExposure.getPercentOfEquity() <= 0) return null;

        // Known-sector equity book, backed out from the flag's own numbers rather than a second
        // recomputation, so this can never disagree with what the flag actually says.
        BigDecimal knownSectorBook = sectorExposure.getValue()
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(sectorExposure.getPercentOfEquity()), 2, RoundingMode.HALF_UP);
        BigDecimal target = knownSectorBook.multiply(SECTOR_PCT_OF_EQUITY_HIGH).divide(BigDecimal.valueOf(100));
        BigDecimal sectorExcess = sectorExposure.getValue().subtract(target).max(BigDecimal.ZERO);
        if (sectorExcess.signum() <= 0) return null;

        List<Holding> members = new ArrayList<>();
        for (Holding h : holdings) {
            if (PortfolioContextService.isMf(h)) continue;
            if (h.getSymbol() == null) continue;
            Optional<Stock> s = safeFindStock(h.getSymbol());
            if (s.isPresent() && sector.equals(s.get().getSector())) members.add(h);
        }
        if (members.isEmpty()) return null;

        Holding chosen = pickLowestQuality(members);
        String selectionReason = qualitySelectionReason(members, chosen);

        if (chosen.getCurrentPrice() == null || chosen.getCurrentPrice().signum() <= 0) return null;
        BigDecimal currentValue = chosen.getCurrentValue();
        BigDecimal excessValue = sectorExcess.min(currentValue).max(BigDecimal.ZERO);
        if (excessValue.signum() <= 0) return null;

        String note = excessValue.compareTo(sectorExcess) < 0
            ? " Trimming this alone will not fully resolve the sector concentration — "
              + chosen.getSymbol() + " is worth less than the full excess; consider trimming another "
              + sector + " holding too."
            : "";

        return sizeAndPrice(flag.getType(), sector, chosen, excessValue, currentValue,
            sectorExposure.getPercentOfEquity(), "30% of known-sector equity guideline",
            flag.getMessage() + note, selectionReason);
    }

    /** Among the flagged sector's holdings, the one whose disclosed fundamentals are weakest — so a
     *  correlation-risk trim also improves average holding quality rather than being arbitrary. */
    private Holding pickLowestQuality(List<Holding> members) {
        Holding worst = null;
        double worstScore = Double.POSITIVE_INFINITY;
        for (Holding h : members) {
            Optional<Stock> s = safeFindStock(h.getSymbol());
            if (s.isEmpty()) continue;
            FactorScore fs = qualityFactor.score(s.get().getRoe(), s.get().getDebtToEquity(), null, null, null);
            if (!fs.isUsable()) continue;
            if (fs.score() < worstScore) {
                worstScore = fs.score();
                worst = h;
            }
        }
        if (worst != null) return worst;
        // No usable quality signal for any member — fall back to the largest holding, since
        // trimming it makes the most difference and nothing here is guessed.
        return members.stream().max(Comparator.comparing(Holding::getCurrentValue)).orElse(members.get(0));
    }

    private String qualitySelectionReason(List<Holding> members, Holding chosen) {
        if (members.size() == 1) return "Only " + chosen.getSymbol() + " in this sector is held.";
        Optional<Stock> s = safeFindStock(chosen.getSymbol());
        if (s.isPresent()) {
            FactorScore fs = qualityFactor.score(s.get().getRoe(), s.get().getDebtToEquity(), null, null, null);
            if (fs.isUsable()) {
                return chosen.getSymbol() + " picked to trim: " + fs.explanation()
                    + " — the weakest quality signal among your " + members.size() + " holdings in this sector.";
            }
        }
        return chosen.getSymbol() + " picked to trim as the largest holding in this sector "
            + "(quality data unavailable for the others to compare).";
    }

    private TrimSuggestionDto sizeAndPrice(String triggerType, String triggerLabel, Holding h,
                                           BigDecimal excessValue, BigDecimal currentValue,
                                           Double currentPercent, String basis, String reason,
                                           String selectionReason) {
        BigDecimal price = h.getCurrentPrice();
        BigDecimal unitsToSell = excessValue.divide(price, 4, RoundingMode.DOWN)
            .min(h.getQuantity()).max(BigDecimal.ZERO);
        if (unitsToSell.signum() <= 0) return null;
        BigDecimal sellValue = unitsToSell.multiply(price).setScale(2, RoundingMode.HALF_UP);

        TrimSuggestionDto.TrimSuggestionDtoBuilder builder = TrimSuggestionDto.builder()
            .triggerType(triggerType).triggerLabel(triggerLabel)
            .symbol(h.getSymbol()).name(h.getName())
            .currentValue(currentValue).currentPercent(currentPercent).basis(basis)
            .suggestedSellUnits(unitsToSell).suggestedSellValue(sellValue)
            .reason(reason).selectionReason(selectionReason);

        List<Transaction> txns = transactionRepository.findByHoldingIdOrderByTransactionDateAsc(h.getId());
        List<TaxLot> openLots = openLotsFifo(txns);
        if (openLots.isEmpty()) {
            builder.taxImpactGap("No purchase-transaction history recorded for this holding, so the "
                + "real tax-lot cost basis is unknown — tax impact is not shown rather than estimated.");
            return builder.build();
        }

        TaxImpactDto tax = computeTaxImpact(openLots, unitsToSell, price);
        builder.taxImpact(tax);
        return builder.build();
    }

    private TaxImpactDto computeTaxImpact(List<TaxLot> openLots, BigDecimal unitsToSell, BigDecimal price) {
        LocalDate today = LocalDate.now();
        DisposalCalculator.DisposalResult r = disposalCalculator.sell(
            openLots, unitsToSell, price, today,
            null, 0,                              // exit load: not tracked per scheme, not applied
            FyExemptionLedger.empty(today), BigDecimal.ZERO); // surcharge: not tracked, assumed nil

        List<String> caveats = new ArrayList<>();
        boolean hasPreGrandfatherLot = openLots.stream()
            .anyMatch(l -> l.acquiredOn() != null && !l.acquiredOn().isAfter(CapitalGainsRates.GRANDFATHER_DATE));
        if (hasPreGrandfatherLot) {
            caveats.add("Some units were acquired on or before 31 Jan 2018 and grandfathering "
                + "(step-up to that date's fair market value) is not applied — the fair market "
                + "value on that date is not recorded, so this may overstate the gain slightly.");
        }

        return TaxImpactDto.builder()
            .unitsSold(r.unitsSold())
            .grossProceeds(r.grossProceeds())
            .exitLoad(r.exitLoad())
            .shortTermGain(r.shortTermGain())
            .longTermGain(r.longTermGain())
            .exemptionUsed(r.exemptionUsed())
            .tax(r.tax())
            .netProceeds(r.netProceeds())
            .deferralAdvice(r.deferralAdvice())
            .caveats(caveats)
            .build();
    }

    /**
     * Replays the FIFO-ordered BUY/SELL ledger to recover the lots that are still open today —
     * FIFO because that is both the statutory default for units and what {@code DisposalCalculator}
     * itself assumes. This never re-derives cost basis math; it only reconstructs which lots exist.
     */
    private List<TaxLot> openLotsFifo(List<Transaction> txns) {
        List<TaxLot> lots = new ArrayList<>();
        List<BigDecimal> remaining = new ArrayList<>();
        for (Transaction t : txns) {
            if (t.getQuantity() == null || t.getPrice() == null || t.getTransactionDate() == null) continue;
            if (t.getType() == Transaction.TransactionType.BUY) {
                lots.add(new TaxLot("TXN-" + t.getId(), t.getTransactionDate(), t.getQuantity(), t.getPrice(), null));
                remaining.add(t.getQuantity());
            } else {
                BigDecimal toSell = t.getQuantity();
                for (int i = 0; i < lots.size() && toSell.signum() > 0; i++) {
                    BigDecimal avail = remaining.get(i);
                    if (avail.signum() <= 0) continue;
                    BigDecimal consume = avail.min(toSell);
                    remaining.set(i, avail.subtract(consume));
                    toSell = toSell.subtract(consume);
                }
            }
        }
        List<TaxLot> open = new ArrayList<>();
        for (int i = 0; i < lots.size(); i++) {
            BigDecimal rem = remaining.get(i);
            if (rem.signum() > 0) {
                TaxLot l = lots.get(i);
                open.add(new TaxLot(l.lotId(), l.acquiredOn(), rem, l.costPerUnit(), null));
            }
        }
        return open;
    }

    private Optional<Stock> safeFindStock(String symbol) {
        try {
            return stockRepository.findBySymbol(symbol);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Same stripping convention {@code PortfolioContextService} uses for stock exposure labels. */
    private Holding findByDisplayLabel(String label, List<Holding> holdings) {
        if (label == null) return null;
        for (Holding h : holdings) {
            if (PortfolioContextService.isMf(h) || h.getSymbol() == null) continue;
            String stripped = h.getSymbol().replace(".NS", "").replace(".BO", "");
            if (stripped.equalsIgnoreCase(label)) return h;
        }
        return null;
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private static Double pct(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.compareTo(BigDecimal.ZERO) <= 0) return null;
        return part.divide(whole, 6, RoundingMode.HALF_UP).doubleValue() * 100;
    }
}
