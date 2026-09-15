package com.marketai.reconciliation.dto;

import lombok.Builder;
import lombok.Data;

/**
 * One flagged data-integrity problem from any financial domain (portfolio holdings, FD, RD,
 * net worth). Mirrors the shape {@code IntegrityReportDto.Issue} already established for
 * portfolio holdings — this generalizes that same "flag it, never silently show a wrong
 * number" pattern to every other domain that previously had no equivalent.
 */
@Data
@Builder
public class ReconciliationIssue {
    // PORTFOLIO | FD | RD | NET_WORTH
    private String domain;
    // e.g. UNVERIFIABLE_NAME, DUPLICATE_SYMBOL, MISMATCHED_TICKER, MATURED_IDLE,
    // ORPHANED_RENEWAL_LINK, NET_WORTH_DRIFT
    private String type;
    private String description;
    // HIGH | MEDIUM | LOW
    private String severity;
    private Long referenceId;
}
