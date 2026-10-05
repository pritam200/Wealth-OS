package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.SourceType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The confidence in a canonical transaction, from its sources alone.
 *
 * <p>Each source contributes its type's base confidence scaled by how sure that record was
 * (an extractor's score for an email; 1 for a structured feed). The transaction takes the best
 * of them, plus 0.05 for each further independent source type that reports it, capped at 1.
 * A disagreement between sources caps it at 0.5. Nothing else enters, so the number can always
 * be reproduced by hand.
 */
@Component
public class ConfidenceCalculator {

    public static final double CORROBORATION_BONUS = 0.05;
    public static final double CONFLICT_CAP = 0.5;

    public record Observation(SourceType type, double recordConfidence) {}

    public double confidence(List<Observation> observations, boolean conflict) {
        if (observations.isEmpty()) return 0.0;
        double best = 0;
        for (Observation o : observations) {
            double q = Math.max(0.0, Math.min(1.0, o.recordConfidence()));
            best = Math.max(best, o.type().baseConfidence() * q);
        }
        long distinct = observations.stream().map(Observation::type).distinct().count();
        double c = Math.min(1.0, best + CORROBORATION_BONUS * (distinct - 1));
        if (conflict) c = Math.min(c, CONFLICT_CAP);
        return Math.round(c * 1000.0) / 1000.0;
    }
}
