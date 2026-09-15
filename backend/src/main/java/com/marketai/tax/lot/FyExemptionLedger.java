package com.marketai.tax.lot;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How much of the financial year's long-term exemption is left.
 *
 * <p><b>This is the single most commonly mis-implemented rule in Indian retail finance.</b> The
 * ₹1,25,000 exemption is a <em>per-financial-year aggregate across all qualifying gains</em> —
 * listed equity shares, equity-oriented mutual funds and business trust units combined — for the
 * assessee. It is not per transaction, not per scheme, not per folio.
 *
 * <p>Applying it per redemption understates tax by ₹15,625 for every additional redemption in
 * the year, and the error compounds with each one. A user acting on that number would under-set
 * aside for tax and find out at filing.
 *
 * <p>The ledger is therefore stateful across the whole year, not a constant subtracted per sale.
 */
public record FyExemptionLedger(int fyStartYear, BigDecimal realisedLtcgToDate) {

    /** Indian financial years run 1 April to 31 March. */
    public static int fyStartYearFor(LocalDate date) {
        return date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    }

    public static FyExemptionLedger empty(LocalDate asOf) {
        return new FyExemptionLedger(fyStartYearFor(asOf), BigDecimal.ZERO);
    }

    public BigDecimal remainingExemption() {
        BigDecimal left = CapitalGainsRates.LTCG_EXEMPTION.subtract(realisedLtcgToDate);
        return left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    /** The ledger after realising a further long-term gain. */
    public FyExemptionLedger plus(BigDecimal additionalLtcg) {
        BigDecimal more = additionalLtcg == null ? BigDecimal.ZERO : additionalLtcg;
        return new FyExemptionLedger(fyStartYear,
            realisedLtcgToDate.add(more.max(BigDecimal.ZERO)));
    }

    public String label() {
        return String.format("FY %d-%02d", fyStartYear, (fyStartYear + 1) % 100);
    }
}
