package com.marketai.tax.lot;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What a redemption actually leaves in your hand, after exit load and tax.
 *
 * <p>Never reports a gross gain. The figure a user needs before deciding to sell is net
 * proceeds, and the gap between gross and net — exit load, short-term rate, a partly-consumed
 * annual exemption — is frequently large enough to reverse the decision.
 *
 * <p>Lots are consumed FIFO, which is both the statutory default for units and the convention
 * every Indian registrar applies.
 */
@Component
public class DisposalCalculator {

    public record LotDisposal(String lotId, BigDecimal units, boolean longTerm,
                              BigDecimal cost, BigDecimal proceeds, BigDecimal gain) {}

    public record DisposalResult(BigDecimal unitsSold, BigDecimal grossProceeds,
                                 BigDecimal exitLoad,
                                 BigDecimal shortTermGain, BigDecimal longTermGain,
                                 BigDecimal exemptionUsed,
                                 BigDecimal tax, BigDecimal netProceeds,
                                 List<LotDisposal> lots,
                                 FyExemptionLedger ledgerAfter,
                                 String deferralAdvice) {

        public BigDecimal totalDrag() { return exitLoad.add(tax); }
    }

    /**
     * @param exitLoadRate      as a fraction, applied to units held under the load period
     * @param exitLoadDays      days within which the load applies — per scheme, not a constant
     * @param surchargeRate     the user's surcharge, capped at 15% for long-term gains
     */
    public DisposalResult sell(List<TaxLot> lots, BigDecimal unitsToSell,
                               BigDecimal salePricePerUnit, LocalDate disposalDate,
                               BigDecimal exitLoadRate, int exitLoadDays,
                               FyExemptionLedger ledger, BigDecimal surchargeRate) {

        List<TaxLot> fifo = new ArrayList<>(lots == null ? List.of() : lots);
        fifo.sort(Comparator.comparing(TaxLot::acquiredOn));

        BigDecimal remaining = unitsToSell;
        BigDecimal grossProceeds = BigDecimal.ZERO;
        BigDecimal exitLoad = BigDecimal.ZERO;
        BigDecimal stGain = BigDecimal.ZERO;
        BigDecimal ltGain = BigDecimal.ZERO;
        List<LotDisposal> disposals = new ArrayList<>();
        LocalDate soonestLongTerm = null;

        for (TaxLot lot : fifo) {
            if (remaining.signum() <= 0) break;

            BigDecimal take = remaining.min(lot.units());
            BigDecimal proceeds = take.multiply(salePricePerUnit);
            BigDecimal cost = take.multiply(lot.effectiveCostPerUnit(salePricePerUnit));

            long daysHeld = ChronoUnit.DAYS.between(lot.acquiredOn(), disposalDate);
            if (exitLoadRate != null && daysHeld < exitLoadDays) {
                exitLoad = exitLoad.add(proceeds.multiply(exitLoadRate));
            }

            boolean longTerm = lot.isLongTermAsOf(disposalDate);
            BigDecimal gain = proceeds.subtract(cost);
            if (longTerm) {
                ltGain = ltGain.add(gain);
            } else {
                stGain = stGain.add(gain);
                LocalDate turnsLong = lot.acquiredOn()
                    .plusMonths(CapitalGainsRates.LONG_TERM_MONTHS);
                if (soonestLongTerm == null || turnsLong.isBefore(soonestLongTerm)) {
                    soonestLongTerm = turnsLong;
                }
            }

            disposals.add(new LotDisposal(lot.lotId(), take, longTerm,
                cost.setScale(2, RoundingMode.HALF_UP),
                proceeds.setScale(2, RoundingMode.HALF_UP),
                gain.setScale(2, RoundingMode.HALF_UP)));

            grossProceeds = grossProceeds.add(proceeds);
            remaining = remaining.subtract(take);
        }

        // The exemption is consumed from a running financial-year balance, not applied afresh
        // to each sale. This is the rule that is most often got wrong.
        BigDecimal exemptionAvailable = ledger == null
            ? CapitalGainsRates.LTCG_EXEMPTION : ledger.remainingExemption();
        BigDecimal exemptionUsed = ltGain.max(BigDecimal.ZERO).min(exemptionAvailable);
        BigDecimal taxableLt = ltGain.subtract(exemptionUsed).max(BigDecimal.ZERO);

        BigDecimal baseTax = taxableLt.multiply(CapitalGainsRates.LTCG_RATE)
            .add(stGain.max(BigDecimal.ZERO).multiply(CapitalGainsRates.STCG_RATE));

        BigDecimal surcharge = baseTax.multiply(capSurcharge(surchargeRate));
        BigDecimal tax = baseTax.add(surcharge);
        tax = tax.add(tax.multiply(CapitalGainsRates.CESS_RATE))
            .setScale(2, RoundingMode.HALF_UP);

        exitLoad = exitLoad.setScale(2, RoundingMode.HALF_UP);
        grossProceeds = grossProceeds.setScale(2, RoundingMode.HALF_UP);

        return new DisposalResult(
            unitsToSell.subtract(remaining),
            grossProceeds, exitLoad,
            stGain.setScale(2, RoundingMode.HALF_UP),
            ltGain.setScale(2, RoundingMode.HALF_UP),
            exemptionUsed.setScale(2, RoundingMode.HALF_UP),
            tax,
            grossProceeds.subtract(exitLoad).subtract(tax),
            List.copyOf(disposals),
            (ledger == null ? FyExemptionLedger.empty(disposalDate) : ledger).plus(ltGain),
            deferralAdvice(soonestLongTerm, disposalDate, stGain, exitLoadDays, fifo, exitLoadRate));
    }

    /**
     * "Wait N days and save ₹X" — per the research, the highest-value output of a tax feature
     * and one no Indian platform ships.
     */
    private String deferralAdvice(LocalDate soonestLongTerm, LocalDate disposalDate,
                                  BigDecimal stGain, int exitLoadDays,
                                  List<TaxLot> lots, BigDecimal exitLoadRate) {
        if (soonestLongTerm == null || stGain.signum() <= 0) return null;

        long days = ChronoUnit.DAYS.between(disposalDate, soonestLongTerm);
        if (days <= 0 || days > 120) return null;

        // The saving is the rate differential on the short-term gain that would become long-term.
        BigDecimal saving = stGain
            .multiply(CapitalGainsRates.STCG_RATE.subtract(CapitalGainsRates.LTCG_RATE))
            .setScale(2, RoundingMode.HALF_UP);

        return String.format(
            "Waiting %d day(s), until %s, would move ₹%s of gain from the 20%% short-term rate "
                + "to the 12.5%% long-term rate — around ₹%s less tax%s.",
            days, soonestLongTerm, stGain.setScale(2, RoundingMode.HALF_UP).toPlainString(),
            saving.toPlainString(),
            exitLoadRate != null && exitLoadRate.signum() > 0
                ? ", and may also clear the exit-load window" : "");
    }

    private static BigDecimal capSurcharge(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) return BigDecimal.ZERO;
        return rate.min(CapitalGainsRates.MAX_LTCG_SURCHARGE);
    }
}
