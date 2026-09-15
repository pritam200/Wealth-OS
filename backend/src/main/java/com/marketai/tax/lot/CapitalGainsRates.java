package com.marketai.tax.lot;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Indian capital-gains constants for equity and equity-oriented mutual funds.
 *
 * <p>Current as of September 2026, post the Finance (No.2) Act 2024 changes effective
 * 23 July 2024, and unchanged by Budget 2026.
 *
 * <p>Section references are held as data rather than embedded in user-facing strings because
 * the <b>Income-tax Act 2025 replaces the 1961 Act from 1 April 2026</b> and renumbers these:
 * 111A becomes 196, 112A becomes 198. The rates carry over unchanged; only the citations move.
 */
public final class CapitalGainsRates {

    /** Holding period above which equity gains are long-term. */
    public static final int LONG_TERM_MONTHS = 12;

    /** Short-term capital gains on equity — raised from 15% by the Finance (No.2) Act 2024. */
    public static final BigDecimal STCG_RATE = new BigDecimal("0.20");

    /** Long-term capital gains on equity, without indexation. */
    public static final BigDecimal LTCG_RATE = new BigDecimal("0.125");

    /**
     * The long-term exemption. <b>₹1,25,000, not ₹1,00,000</b> — several live competitor pages
     * still display the old figure.
     */
    public static final BigDecimal LTCG_EXEMPTION = new BigDecimal("125000");

    /** Health and education cess, applied on top of tax plus surcharge. */
    public static final BigDecimal CESS_RATE = new BigDecimal("0.04");

    /** Surcharge on long-term gains under this section is capped at 15%. */
    public static final BigDecimal MAX_LTCG_SURCHARGE = new BigDecimal("0.15");

    /** Grandfathering reference date: cost is stepped up to the fair market value on this day. */
    public static final LocalDate GRANDFATHER_DATE = LocalDate.of(2018, 1, 31);

    /** Statutory citation, which changes on 1 April 2026 without the rates changing. */
    public static String shortTermSection(LocalDate asOf) {
        return asOf != null && !asOf.isBefore(LocalDate.of(2026, 4, 1)) ? "196" : "111A";
    }

    public static String longTermSection(LocalDate asOf) {
        return asOf != null && !asOf.isBefore(LocalDate.of(2026, 4, 1)) ? "198" : "112A";
    }

    private CapitalGainsRates() {}
}
