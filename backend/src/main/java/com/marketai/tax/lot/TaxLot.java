package com.marketai.tax.lot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * One acquisition of units, with the cost basis that applies to it.
 *
 * <p>Lots exist because weighted-average cost — which the portfolio ledger uses, correctly, for
 * "what did this position cost me" — cannot express anything tax needs. Long-term versus
 * short-term is a property of when each parcel was bought, not of the position's average.
 *
 * @param grandfatheredCost for units held before 1 Feb 2018: max(actual cost, FMV on
 *                          31 Jan 2018), capped at the sale price
 */
public record TaxLot(String lotId, LocalDate acquiredOn, BigDecimal units,
                     BigDecimal costPerUnit, BigDecimal fmv20180131) {

    public boolean isLongTermAsOf(LocalDate disposalDate) {
        if (acquiredOn == null || disposalDate == null) return false;
        return ChronoUnit.MONTHS.between(acquiredOn, disposalDate)
            >= CapitalGainsRates.LONG_TERM_MONTHS;
    }

    /**
     * Cost basis per unit, applying grandfathering where it is available.
     *
     * <p>The rule steps the cost up to the 31 Jan 2018 market value but never above the sale
     * price — so the relief can reduce a gain to nil, and cannot manufacture a loss.
     */
    public BigDecimal effectiveCostPerUnit(BigDecimal salePricePerUnit) {
        if (acquiredOn == null || !acquiredOn.isBefore(CapitalGainsRates.GRANDFATHER_DATE.plusDays(1))
                || fmv20180131 == null) {
            return costPerUnit;
        }
        BigDecimal steppedUp = costPerUnit.max(fmv20180131);
        return salePricePerUnit == null ? steppedUp : steppedUp.min(salePricePerUnit);
    }
}
