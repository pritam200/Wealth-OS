package com.marketai.networth.attribution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A net-worth change, decomposed.
 *
 * <p>The sentence this exists to produce — "your net worth rose ₹X: ₹A you saved, ₹B the market
 * gave you, ₹C you spent" — is, per the competitive research, unoccupied in Indian retail
 * finance. Not because the arithmetic is hard, but because separating "money you moved" from
 * "money the market moved" requires knowing which transfers were internal, and most aggregators
 * cannot tell a ₹50,000 bank-to-mutual-fund transfer from ₹50,000 of new saving.
 *
 * @param unexplained closing − opening − Σ components. <b>Reported, never absorbed.</b>
 */
public record AttributionResult(LocalDate from, LocalDate to,
                                BigDecimal openingNetWorth, BigDecimal closingNetWorth,
                                BigDecimal change,
                                List<AttributionComponent> components,
                                BigDecimal unexplained) {

    /** A gap this size or smaller is rupee rounding across many components, not a fault. */
    public static final BigDecimal CLOSURE_TOLERANCE = new BigDecimal("1.00");

    /**
     * Whether the components account for the whole change.
     *
     * <p>When this is false the result must be treated as a reconciliation exception, not
     * presented with the gap quietly folded into one of the categories. A plug would make the
     * arithmetic look right while hiding the very defect the decomposition exists to expose.
     */
    public boolean closes() {
        return unexplained.abs().compareTo(CLOSURE_TOLERANCE) <= 0;
    }

    public Map<AttributionKind, BigDecimal> byKind() {
        return components.stream().collect(Collectors.groupingBy(
            AttributionComponent::kind,
            java.util.LinkedHashMap::new,
            Collectors.reducing(BigDecimal.ZERO, AttributionComponent::amount, BigDecimal::add)));
    }

    /** What the user did: everything except market movement. */
    public BigDecimal behavioural() {
        return components.stream()
            .filter(c -> c.kind().isBehavioural())
            .map(AttributionComponent::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** What the market did. */
    public BigDecimal revaluation() {
        return byKind().getOrDefault(AttributionKind.REVALUATION, BigDecimal.ZERO);
    }

    /**
     * Plain-language summary. Deliberately refuses to produce one when the decomposition does
     * not close — a confident sentence built on numbers that do not add up is worse than no
     * sentence.
     */
    public String summary() {
        if (!closes()) {
            return String.format(
                "Net worth changed by ₹%s, but ₹%s of that cannot yet be explained. "
                    + "No breakdown is shown because it would not add up.",
                scale(change), scale(unexplained));
        }
        return String.format("Net worth %s by ₹%s: ₹%s from what you saved and spent, "
                + "₹%s from market movement.",
            change.signum() >= 0 ? "rose" : "fell", scale(change.abs()),
            scale(behavioural()), scale(revaluation()));
    }

    private static String scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
