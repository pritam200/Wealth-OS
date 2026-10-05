package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.ReconStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * The data-quality score, and the arithmetic behind it.
 *
 * <p>{@code score = verified / (verified + matched + partially matched + pending + unconfirmed +
 * conflict + missing)}, as a percentage. A MATCHED transaction (two weak sources agreeing) is
 * not verified, so it does not count toward the score; the figure is therefore a conservative
 * "how much of this is confirmed by an authoritative source". Duplicates, rejected and reversed
 * rows are not facts about the portfolio and are excluded. Missing transactions are counted
 * because they are facts the ledger lacks. An empty ledger has no score, not a perfect one.
 */
@Component
public class DataQualityScorer {

    public record Score(Double percent, Map<ReconStatus, Long> counts, long considered, String formula) {
        public boolean scored() { return percent != null; }
    }

    public Score score(Map<ReconStatus, Long> counts, long missingIssues) {
        Map<ReconStatus, Long> c = new EnumMap<>(ReconStatus.class);
        for (ReconStatus s : ReconStatus.values()) c.put(s, counts.getOrDefault(s, 0L));
        c.merge(ReconStatus.MISSING, missingIssues, Long::sum);
        long considered = 0;
        for (ReconStatus s : ReconStatus.values()) if (s != ReconStatus.DUPLICATE) considered += c.get(s);
        String formula = "verified / (verified + matched + partially matched + pending + unconfirmed + conflict + missing)";
        if (considered == 0) return new Score(null, c, 0, formula);
        double pct = 100.0 * c.get(ReconStatus.VERIFIED) / considered;
        return new Score(Math.round(pct * 10.0) / 10.0, c, considered, formula);
    }
}
