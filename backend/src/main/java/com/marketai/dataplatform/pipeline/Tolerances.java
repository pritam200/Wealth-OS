package com.marketai.dataplatform.pipeline;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The numeric tolerances matching uses. They are deliberately tight: they exist to absorb the
 * rounding two institutions legitimately differ by, not to guess that two figures are the same.
 */
public final class Tolerances {
    private Tolerances() {}

    /** Two quantities within this many units are equal (units are published to 3-4 decimals). */
    public static final BigDecimal QTY_ABS = new BigDecimal("0.0005");
    /** Two amounts within one rupee are equal (paise rounding). */
    public static final BigDecimal AMOUNT_ABS = new BigDecimal("1.00");
    /** Beyond the absolute tolerance, figures within this fraction are "close" but not equal. */
    public static final BigDecimal REL = new BigDecimal("0.005");
    /** Trade date versus settlement or NAV date. */
    public static final int DATE_DAYS = 2;

    public enum Agreement { EXACT, CLOSE, DISAGREE }

    public static Agreement compare(BigDecimal a, BigDecimal b, BigDecimal abs) {
        BigDecimal diff = a.subtract(b).abs();
        if (diff.compareTo(abs) <= 0) return Agreement.EXACT;
        BigDecimal scale = a.abs().max(b.abs());
        if (scale.signum() > 0 && diff.divide(scale, 8, RoundingMode.HALF_UP).compareTo(REL) <= 0) return Agreement.CLOSE;
        return Agreement.DISAGREE;
    }
}
