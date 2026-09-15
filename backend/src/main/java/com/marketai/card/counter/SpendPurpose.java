package com.marketai.card.counter;

/**
 * What a spend counts toward.
 *
 * <p>The single most common modelling error in card rewards is maintaining one exclusion list
 * per card. Issuers maintain several. Kotak publishes <em>five</em> distinct lists — fee levy,
 * reward exclusion, air-miles exclusion, milestone and fee-waiver exclusion, and a
 * benefit-specific list — with per-variant carve-outs, and states the list "is not exhaustive".
 *
 * <p>ICICI excludes rent, government and education from fee-waiver spend specifically, while
 * counting them elsewhere. So the same ₹10,000 can simultaneously earn rewards and not count
 * toward the annual-fee waiver. A single boolean "excluded" cannot express that.
 */
public enum SpendPurpose {
    REWARD,
    MILESTONE,
    FEE_WAIVER,
    SPECIFIC_BENEFIT
}
