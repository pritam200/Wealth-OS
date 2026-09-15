package com.marketai.reconciliation.check;

import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every registered {@link ReconciliationCheck} and reports what each one found.
 *
 * <p>Two properties matter more than the checks themselves.
 *
 * <p><b>One failing check does not sink the report.</b> If a check throws, that is recorded as
 * an issue in its own right and the rest still run. A reconciliation report that returns nothing
 * because of one bug is worse than useless — it looks exactly like a clean bill of health.
 *
 * <p><b>The set is enumerable.</b> {@link #describeChecks()} lists what is actually run, so
 * "which checks does this system perform?" has an answer that cannot drift from the code.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReconciliationRegistry {

    private final List<ReconciliationCheck> checks;

    public List<ReconciliationIssue> runAll(Long userId) {
        List<ReconciliationIssue> issues = new ArrayList<>();

        for (ReconciliationCheck check : checks) {
            try {
                issues.addAll(check.run(userId));
            } catch (RuntimeException e) {
                log.warn("Reconciliation check {} failed: {}", check.id(), e.toString());
                // A check that cannot run is itself a finding. Swallowing it would let the
                // report claim everything is fine about something it never actually examined.
                issues.add(ReconciliationIssue.builder()
                    .domain(check.domain())
                    .type("CHECK_FAILED")
                    .severity("MEDIUM")
                    .description(String.format(
                        "The '%s' check could not run, so nothing is known about whether this "
                            + "problem exists: %s", check.id(), e.getMessage()))
                    .build());
            }
        }
        return issues;
    }

    /** Every check that runs, id → description. Stable enough to document against. */
    public Map<String, String> describeChecks() {
        Map<String, String> described = new LinkedHashMap<>();
        for (ReconciliationCheck c : checks) {
            described.put(c.id(), c.description());
        }
        return described;
    }

    public int checkCount() { return checks.size(); }
}
