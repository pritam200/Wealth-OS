package com.marketai.scoring.eligibility;

import java.util.List;

/**
 * Whether a stock can honestly be rated at all.
 *
 * <p>Modelled on Value Research, which publishes explicit exclusions rather than scoring
 * everything. A rating produced from three months of price history and a negative net worth is
 * not a weak rating — it is a fabricated one, and presenting it beside a genuine rating in the
 * same five-star scale implies a comparability that does not exist.
 *
 * <p>Ineligibility is a <em>result</em>, not a null. Callers must handle it explicitly, so the
 * reason reaches the user instead of being flattened into an empty screen.
 */
public record RatingEligibility(boolean eligible, List<String> exclusions) {

    public static RatingEligibility permit() {
        return new RatingEligibility(true, List.of());
    }

    public static RatingEligibility deny(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException(
                "An ineligible rating must say why — 'not rated' without a reason is a dead end "
                    + "for the user");
        }
        return new RatingEligibility(false, List.copyOf(reasons));
    }

    /** One line suitable for showing in place of a star rating. */
    public String explanation() {
        return eligible ? "Eligible for rating"
            : "Not rated: " + String.join("; ", exclusions);
    }
}
