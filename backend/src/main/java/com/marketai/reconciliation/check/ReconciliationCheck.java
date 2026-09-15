package com.marketai.reconciliation.check;

import com.marketai.reconciliation.dto.ReconciliationIssue;

import java.util.List;

/**
 * One reconciliation check.
 *
 * <p>Checks are registered rather than hand-called so the set is enumerable — "which checks does
 * this system run, and did each one pass?" is a question a user should be able to get an answer
 * to, and a hard-coded sequence of method calls cannot answer it.
 *
 * <p>A check reports; it never repairs. Silently correcting a discrepancy destroys the evidence
 * of what went wrong, and "the system quietly fixed your numbers" is not something a user can
 * audit.
 */
public interface ReconciliationCheck {

    /** Stable identifier, used in reports and to reference a check in documentation. */
    String id();

    /** Domain this belongs to — PORTFOLIO, LEDGER, INGESTION, NET_WORTH, … */
    String domain();

    /** One line explaining what would be wrong if this check fired. */
    String description();

    /** Empty means the check passed. */
    List<ReconciliationIssue> run(Long userId);
}
