package com.marketai.document.route;

import com.marketai.common.quality.Sufficiency;
import com.marketai.document.classify.ClassificationCandidate;
import com.marketai.document.classify.DocumentClassification;
import com.marketai.document.classify.DocumentClassifier;
import com.marketai.gmail.parser.EmailParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs both selection paths over the same email and records how they compare, without either
 * one affecting the other.
 *
 * <p>This is a shadow-mode harness, not a feature. Its output is the evidence needed to decide
 * whether classifier routing can safely replace the legacy substring scan. Until
 * {@link #cleanRate()} is convincing on real mail and
 * {@link Counters#regressions()} is zero, the legacy path keeps deciding.
 *
 * <p><b>It calls {@code canParse} only, never {@code parse}.</b> Running extraction twice would
 * double the cost of every sync and, worse, could double-apply any side effect a parser has.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SelectionComparator {

    private final DocumentClassifier classifier;
    private final ParserRouter router;

    private final Map<SelectionComparison.Verdict, AtomicLong> counts = new ConcurrentHashMap<>();

    public record Counters(long agree, long agreeNeither, long routedOnly,
                           long legacyOnly, long disagree) {

        public long total() { return agree + agreeNeither + routedOnly + legacyOnly + disagree; }

        /** Cases where switching would change or lose an import. Must be zero before cutover. */
        public long regressions() { return legacyOnly + disagree; }

        public double cleanRate() {
            return total() == 0 ? 0.0 : (double) (agree + agreeNeither) / total();
        }
    }

    /**
     * Compares the two paths for one email.
     *
     * @param legacySelection the parser the live path actually chose, so the comparison reflects
     *                        what really happened rather than a re-simulation of it
     */
    public SelectionComparison compare(String from, String subject, String body,
                                       String legacySelection, List<EmailParser> allParsers) {
        String routedSelection = null;
        try {
            Sufficiency<DocumentClassification> classified =
                classifier.classify(ClassificationCandidate.email(from, subject, body));

            DocumentClassification c = classified.asOptional().orElse(null);
            for (EmailParser p : router.candidatesFor(c, allParsers)) {
                if (p.canParse(from, subject)) {
                    routedSelection = p.getClass().getSimpleName();
                    break;
                }
            }
        } catch (RuntimeException e) {
            // A shadow comparison must never be able to affect the sync it is observing.
            log.warn("Selection comparison failed, ignoring: {}", e.toString());
            return SelectionComparison.of(legacySelection, legacySelection);
        }

        SelectionComparison comparison = SelectionComparison.of(legacySelection, routedSelection);
        counts.computeIfAbsent(comparison.verdict(), k -> new AtomicLong()).incrementAndGet();

        if (comparison.isRegression()) {
            // Logged at WARN because a regression here is the one thing that blocks cutover.
            // Sender and subject are omitted — this line must be safe in any log sink.
            log.warn("Parser selection regression: {}", comparison.detail());
        }
        return comparison;
    }

    public Counters counters() {
        return new Counters(
            get(SelectionComparison.Verdict.AGREE),
            get(SelectionComparison.Verdict.AGREE_NEITHER),
            get(SelectionComparison.Verdict.ROUTED_ONLY),
            get(SelectionComparison.Verdict.LEGACY_ONLY),
            get(SelectionComparison.Verdict.DISAGREE));
    }

    public double cleanRate() { return counters().cleanRate(); }

    public void reset() { counts.clear(); }

    private long get(SelectionComparison.Verdict v) {
        AtomicLong a = counts.get(v);
        return a == null ? 0L : a.get();
    }
}
