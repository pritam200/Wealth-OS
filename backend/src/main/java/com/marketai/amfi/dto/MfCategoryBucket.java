package com.marketai.amfi.dto;

import java.util.Locale;

/**
 * Coarse, UI-friendly normalisation of AMFI's ~40 SEBI scheme categories.
 *
 * <p>Deliberately conservative: {@link #OTHER} is returned whenever the AMFI category string
 * does not clearly identify exactly one bucket. Guessing between two plausible buckets (e.g.
 * calling an unlabelled "Growth" close-ended scheme LARGE_CAP) would produce a category that
 * looks authoritative but isn't, so we don't.
 */
public enum MfCategoryBucket {
    LARGE_CAP,
    MID_CAP,
    SMALL_CAP,
    LARGE_AND_MID_CAP,
    FLEXI_CAP,
    MULTI_CAP,
    ELSS,
    INDEX,
    SECTORAL_THEMATIC,
    HYBRID,
    DEBT,
    LIQUID,
    GOLD_SILVER,
    INTERNATIONAL,
    OTHER;

    /**
     * Derive a bucket from an AMFI category string such as
     * {@code "Equity Scheme - Large Cap Fund"}.
     *
     * <p>Rule order matters and is chosen so that the more specific label always wins over a
     * substring of it:
     * <ul>
     *   <li>"Gold ETF" must not fall through to INDEX just because it says "ETF"</li>
     *   <li>"Large &amp; Mid Cap Fund" must not fall through to LARGE_CAP</li>
     *   <li>"Debt Scheme - Liquid Fund" is LIQUID; every other "Debt Scheme" is DEBT</li>
     * </ul>
     *
     * @return the bucket, never null; {@link #OTHER} when the input is null, blank or unclear
     */
    public static MfCategoryBucket from(String category) {
        if (category == null) return OTHER;
        String c = category.toLowerCase(Locale.ENGLISH);
        if (c.trim().isEmpty()) return OTHER;

        // Commodity before INDEX — most of these are ETFs and would otherwise be misread as index funds.
        if (c.contains("gold") || c.contains("silver")) return GOLD_SILVER;

        // Overseas exposure before INDEX for the same reason (overseas index FoFs exist).
        if (c.contains("overseas") || c.contains("international") || c.contains("global")) return INTERNATIONAL;

        if (c.contains("elss") || c.contains("equity linked saving")) return ELSS;

        if (c.contains("index") || c.contains("etf")) return INDEX;

        // "Large & Mid Cap" before "Large Cap" / "Mid Cap".
        if (c.contains("large & mid") || c.contains("large and mid")) return LARGE_AND_MID_CAP;
        if (c.contains("large cap")) return LARGE_CAP;
        if (c.contains("mid cap")) return MID_CAP;
        if (c.contains("small cap")) return SMALL_CAP;
        if (c.contains("flexi cap")) return FLEXI_CAP;
        if (c.contains("multi cap")) return MULTI_CAP;

        if (c.contains("sectoral") || c.contains("thematic")) return SECTORAL_THEMATIC;

        if (c.contains("hybrid") || c.contains("balanced") || c.contains("arbitrage")) return HYBRID;

        // Liquid before the generic debt sweep: AMFI files it as "Debt Scheme - Liquid Fund".
        if (c.contains("liquid")) return LIQUID;
        if (c.contains("debt scheme") || c.contains("gilt") || c.contains("money market")
                || c.contains("corporate bond") || c.contains("credit risk")
                || c.contains("banking and psu") || c.contains("floater")
                || c.contains("overnight") || c.contains("duration")) {
            return DEBT;
        }

        // Everything left over is genuinely ambiguous: equity styles with no cap mandate
        // (Value / Contra / Focused / Dividend Yield), Solution Oriented schemes, domestic
        // FoFs, and the bare "(Income)" / "(Growth)" labels on close-ended schemes.
        return OTHER;
    }
}
