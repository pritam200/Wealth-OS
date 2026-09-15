package com.marketai.document.confidence;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cross-field arithmetic checks — the strongest extraction signal available, because it uses
 * the document's own internal redundancy rather than anything the extractor claims.
 *
 * <p>A misread digit almost always breaks an identity that the document itself asserts: the
 * line items stop summing to the total, or quantity × price stops reconciling to the net
 * amount. That makes these checks independent of how the value was obtained, which is exactly
 * what a model's self-reported confidence is not.
 */
public final class ArithmeticValidator {

    private ArithmeticValidator() {}

    /**
     * Tolerance for rounding differences that are genuinely present in real statements —
     * per-unit prices rounded to paise, brokerage apportioned across lots. Wide enough to
     * absorb legitimate rounding, far too narrow to absorb a misread digit.
     */
    private static final BigDecimal TOLERANCE = new BigDecimal("1.00");

    public record Check(boolean passed, String detail) {}

    /** quantity × price ± charges should reconcile to the stated net amount. */
    public static Check tradeReconciles(BigDecimal quantity, BigDecimal price,
                                        BigDecimal charges, BigDecimal netAmount) {
        if (quantity == null || price == null || netAmount == null) {
            return new Check(false, "missing one of quantity/price/netAmount");
        }
        BigDecimal gross = quantity.multiply(price);
        BigDecimal expected = charges == null ? gross : gross.add(charges);
        BigDecimal diff = expected.subtract(netAmount).abs();

        return diff.compareTo(TOLERANCE) <= 0
            ? new Check(true, null)
            : new Check(false, String.format(
                "qty %s x price %s%s = %s but net amount reads %s (off by %s)",
                quantity.toPlainString(), price.toPlainString(),
                charges == null ? "" : " + charges " + charges.toPlainString(),
                expected.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                netAmount.toPlainString(), diff.setScale(2, RoundingMode.HALF_UP).toPlainString()));
    }

    /** opening + credits − debits should reconcile to the stated closing balance. */
    public static Check statementBalances(BigDecimal opening, BigDecimal credits,
                                          BigDecimal debits, BigDecimal closing) {
        if (opening == null || credits == null || debits == null || closing == null) {
            return new Check(false, "missing one of opening/credits/debits/closing");
        }
        BigDecimal expected = opening.add(credits).subtract(debits);
        BigDecimal diff = expected.subtract(closing).abs();

        return diff.compareTo(TOLERANCE) <= 0
            ? new Check(true, null)
            : new Check(false, String.format(
                "opening %s + credits %s - debits %s = %s but closing reads %s (off by %s)",
                opening.toPlainString(), credits.toPlainString(), debits.toPlainString(),
                expected.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                closing.toPlainString(), diff.setScale(2, RoundingMode.HALF_UP).toPlainString()));
    }

    /** units × NAV should reconcile to the stated amount for a mutual-fund transaction. */
    public static Check mfReconciles(BigDecimal units, BigDecimal nav, BigDecimal amount) {
        if (units == null || nav == null || amount == null) {
            return new Check(false, "missing one of units/nav/amount");
        }
        BigDecimal diff = units.multiply(nav).subtract(amount).abs();
        return diff.compareTo(TOLERANCE) <= 0
            ? new Check(true, null)
            : new Check(false, String.format("units %s x NAV %s does not reconcile to amount %s (off by %s)",
                units.toPlainString(), nav.toPlainString(), amount.toPlainString(),
                diff.setScale(2, RoundingMode.HALF_UP).toPlainString()));
    }
}
