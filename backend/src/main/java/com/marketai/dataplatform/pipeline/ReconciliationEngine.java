package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.ReconStatus;
import com.marketai.dataplatform.domain.SourceType;
import com.marketai.dataplatform.pipeline.Tolerances.Agreement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The source-of-truth rules for one transaction.
 *
 * <ul>
 *   <li>An authoritative source verifies; where a weaker source disagrees, the authoritative
 *       figures are used and the disagreement is reported.</li>
 *   <li>Without an authoritative source, two independent sources agreeing is MATCHED.</li>
 *   <li>A single weak source waits; once the grace period passes it is UNCONFIRMED — never
 *       quietly treated as fact.</li>
 *   <li>Sources that cannot be ranked against each other, or that disagree with no authority to
 *       settle it, are a CONFLICT.</li>
 * </ul>
 */
@Component
public class ReconciliationEngine {

    public record Evaluation(ReconStatus status, boolean sourcesDisagree, boolean authorityOverrides) {}

    /** The grace period before a single non-authoritative report becomes UNCONFIRMED. */
    public static final Duration DEFAULT_GRACE = Duration.ofDays(7);

    public Evaluation evaluate(List<SourceObservation> obs, LocalDateTime firstIngestedAt, LocalDateTime now, Duration grace) {
        if (obs.isEmpty()) return new Evaluation(ReconStatus.PENDING, false, false);
        int topRank = obs.stream().mapToInt(SourceObservation::rank).min().orElse(99);
        List<SourceObservation> tops = obs.stream().filter(o -> o.rank() == topRank).toList();
        boolean topsDisagree = anyDisagreement(tops);
        boolean anyDisagree = anyDisagreement(obs);
        boolean topAuthoritative = tops.get(0).type().authoritative();

        if (topsDisagree) return new Evaluation(ReconStatus.CONFLICT, true, false);
        if (anyDisagree) {
            // Different ranks disagree. An authority settles it; without one nothing can.
            return topAuthoritative
                ? new Evaluation(ReconStatus.PARTIALLY_MATCHED, true, true)
                : new Evaluation(ReconStatus.CONFLICT, true, false);
        }
        if (topAuthoritative) return new Evaluation(ReconStatus.VERIFIED, false, false);
        long distinct = obs.stream().map(SourceObservation::type).distinct().count();
        if (distinct >= 2) return new Evaluation(ReconStatus.MATCHED, false, false);
        boolean expired = firstIngestedAt != null && Duration.between(firstIngestedAt, now).compareTo(grace) > 0;
        return new Evaluation(expired ? ReconStatus.UNCONFIRMED : ReconStatus.PENDING, false, false);
    }

    /**
     * The figures a canonical transaction carries: each field from the most reliable source that
     * stated it. A bank's net amount is used even if only an email stated the quantity.
     */
    public Consolidated consolidate(List<SourceObservation> obs) {
        List<SourceObservation> ranked = obs.stream()
            .sorted(Comparator.comparingInt(SourceObservation::rank)
                .thenComparing(Comparator.comparingDouble(SourceObservation::recordConfidence).reversed())
                .thenComparing(o -> o.timestamp() == null ? LocalDateTime.MIN : o.timestamp(), Comparator.reverseOrder()))
            .toList();
        SourceObservation lead = ranked.get(0);
        return new Consolidated(lead,
            first(ranked, SourceObservation::date), first(ranked, SourceObservation::quantity),
            first(ranked, SourceObservation::unitPrice), first(ranked, SourceObservation::gross),
            first(ranked, SourceObservation::fees), first(ranked, SourceObservation::taxes),
            first(ranked, SourceObservation::net));
    }

    public record Consolidated(SourceObservation lead, java.time.LocalDate date, BigDecimal quantity, BigDecimal unitPrice,
                               BigDecimal gross, BigDecimal fees, BigDecimal taxes, BigDecimal net) {}

    private static <T> T first(List<SourceObservation> ranked, java.util.function.Function<SourceObservation, T> f) {
        Optional<T> v = ranked.stream().map(f).filter(java.util.Objects::nonNull).findFirst();
        return v.orElse(null);
    }

    static boolean anyDisagreement(List<SourceObservation> obs) {
        for (int i = 0; i < obs.size(); i++)
            for (int j = i + 1; j < obs.size(); j++)
                if (disagree(obs.get(i), obs.get(j))) return true;
        return false;
    }

    /** Whether two reports of the same event differ on a comparable figure beyond tolerance. */
    static boolean disagree(SourceObservation a, SourceObservation b) {
        if (a.quantity() != null && b.quantity() != null
                && Tolerances.compare(a.quantity(), b.quantity(), Tolerances.QTY_ABS) == Agreement.DISAGREE) return true;
        BigDecimal gap = null;
        for (BigDecimal x : new BigDecimal[]{a.net(), a.gross()})
            for (BigDecimal y : new BigDecimal[]{b.net(), b.gross()}) {
                if (x == null || y == null) continue;
                BigDecimal g = x.subtract(y).abs();
                if (gap == null || g.compareTo(gap) < 0) gap = g;
            }
        if (gap == null) return false;
        BigDecimal scale = BigDecimal.ZERO;
        for (BigDecimal v : new BigDecimal[]{a.net(), a.gross(), b.net(), b.gross()}) if (v != null) scale = scale.max(v.abs());
        if (gap.compareTo(Tolerances.AMOUNT_ABS) <= 0) return false;
        return scale.signum() == 0 || gap.divide(scale, 8, java.math.RoundingMode.HALF_UP).compareTo(Tolerances.REL) > 0;
    }

    public static boolean isAuthoritative(SourceType t) { return t.authoritative(); }
}
