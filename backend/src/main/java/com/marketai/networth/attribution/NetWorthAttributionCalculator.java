package com.marketai.networth.attribution;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Decomposes a net-worth change into what the user did and what the market did.
 *
 * <p>Deliberately a pure calculator: it takes opening and closing values plus the period's
 * events, and returns a decomposition. No repositories, no clock, no I/O — so the arithmetic
 * that underwrites the headline number on the dashboard can be tested exhaustively rather than
 * through a database.
 *
 * <p><b>Revaluation is supplied, not inferred.</b> Computing it as
 * {@code change − everything else} would make the decomposition close by construction and
 * therefore prove nothing: every missing transaction and every double-counted fee would silently
 * become "market movement". The caller computes it from price deltas on held quantities, and
 * this class checks that the independently-derived parts agree.
 */
@Component
public class NetWorthAttributionCalculator {

    /**
     * @param opening      net worth at the start of the period
     * @param closing      net worth at the end
     * @param components   every known contribution, including an independently computed
     *                     REVALUATION. Internal transfers must already net to zero — see
     *                     {@code LedgerTransferService}, which guarantees this at the ledger.
     */
    public AttributionResult attribute(LocalDate from, LocalDate to,
                                       BigDecimal opening, BigDecimal closing,
                                       List<AttributionComponent> components) {

        BigDecimal open = opening == null ? BigDecimal.ZERO : opening;
        BigDecimal close = closing == null ? BigDecimal.ZERO : closing;
        List<AttributionComponent> parts = components == null ? List.of() : List.copyOf(components);

        BigDecimal change = close.subtract(open);
        BigDecimal explained = parts.stream()
            .map(AttributionComponent::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Positive means the change is larger than anything we can account for — money appeared
        // from somewhere untracked. Negative means we over-explain it.
        BigDecimal unexplained = change.subtract(explained);

        return new AttributionResult(from, to,
            open.setScale(2, RoundingMode.HALF_UP),
            close.setScale(2, RoundingMode.HALF_UP),
            change.setScale(2, RoundingMode.HALF_UP),
            parts,
            unexplained.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Revaluation on one holding: the price move applied to the quantity actually held.
     *
     * <p>Uses the <em>opening</em> quantity on purpose. Gains on units bought mid-period belong
     * to the part of the period they were held for, and attributing a full-period price move to
     * them would credit the market with returns on money that was not yet invested. Purchases
     * enter the decomposition as CONTRIBUTION; only their subsequent movement is revaluation,
     * which the caller supplies separately if it tracks intra-period lots.
     */
    public BigDecimal revaluation(BigDecimal openingQuantity,
                                  BigDecimal openingPrice, BigDecimal closingPrice) {
        if (openingQuantity == null || openingPrice == null || closingPrice == null) {
            return BigDecimal.ZERO;
        }
        return closingPrice.subtract(openingPrice)
            .multiply(openingQuantity)
            .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Modified Dietz return for the period — the industry-standard way to measure return when
     * money moved in and out partway through.
     *
     * <pre>
     *   R = (B − A − F) / (A + Σ Wᵢ·Fᵢ)        Wᵢ = (D − dᵢ) / D
     * </pre>
     *
     * where A is the opening value, B the closing value, F the net external flow, dᵢ the day of
     * flow i and D the days in the period. Weighting each flow by the fraction of the period it
     * was invested is what stops a large deposit on the final day from looking like a loss.
     *
     * <p>Returns null when the weighted denominator is zero or negative — a return percentage on
     * no invested capital is not a small number, it is undefined, and printing one would be
     * fabrication.
     */
    public BigDecimal modifiedDietz(BigDecimal opening, BigDecimal closing,
                                    List<Flow> flows, LocalDate from, LocalDate to) {
        if (opening == null || closing == null || from == null || to == null) return null;

        long totalDays = ChronoUnit.DAYS.between(from, to);
        if (totalDays <= 0) return null;

        List<Flow> netFlows = flows == null ? List.of() : flows;

        BigDecimal netFlow = netFlows.stream()
            .map(Flow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal weightedFlow = BigDecimal.ZERO;
        for (Flow f : netFlows) {
            if (f.date() == null || f.amount() == null) continue;
            long daysHeld = ChronoUnit.DAYS.between(f.date(), to);
            // A flow outside the period contributes no weight rather than a negative one.
            daysHeld = Math.max(0, Math.min(daysHeld, totalDays));
            BigDecimal weight = BigDecimal.valueOf(daysHeld)
                .divide(BigDecimal.valueOf(totalDays), 10, RoundingMode.HALF_UP);
            weightedFlow = weightedFlow.add(f.amount().multiply(weight));
        }

        BigDecimal denominator = opening.add(weightedFlow);
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) return null;

        return closing.subtract(opening).subtract(netFlow)
            .divide(denominator, 6, RoundingMode.HALF_UP);
    }

    /** An external cash flow: positive in, negative out. */
    public record Flow(LocalDate date, BigDecimal amount) {}

    /** Convenience: sum a list of components into the signed total they represent. */
    public static BigDecimal total(List<AttributionComponent> components) {
        if (components == null) return BigDecimal.ZERO;
        return components.stream().map(AttributionComponent::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Builds the component list, filtering zero amounts that would only add noise. */
    public static List<AttributionComponent> components(AttributionComponent... parts) {
        List<AttributionComponent> kept = new ArrayList<>();
        for (AttributionComponent p : parts) {
            if (p != null && p.amount().signum() != 0) kept.add(p);
        }
        return List.copyOf(kept);
    }
}
