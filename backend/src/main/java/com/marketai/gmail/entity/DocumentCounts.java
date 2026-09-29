package com.marketai.gmail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What one source document (an email body or a statement attachment) produced, so every
 * extracted event is accounted for: imported, already on record, conflicting with a record,
 * waiting for a person, or failed.
 *
 * <p>{@link #outcome} is SUCCESS (everything found is accounted for), PARTIAL_SUCCESS (part of
 * the document could not be read, or some lines could not be saved), RECONCILIATION_REQUIRED
 * (something needs a person: a review item or a conflict), FAILED (nothing could be taken from
 * it), or NO_TRANSACTION (read in full, nothing financial in it).
 */
@Embeddable
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DocumentCounts {

    public static final String SUCCESS = "SUCCESS";
    public static final String PARTIAL_SUCCESS = "PARTIAL_SUCCESS";
    public static final String RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";
    public static final String FAILED = "FAILED";
    public static final String NO_TRANSACTION = "NO_TRANSACTION";

    @Column(name = "events_extracted")
    private Integer extracted;

    @Column(name = "events_imported")
    private Integer imported;

    @Column(name = "events_duplicate")
    private Integer duplicates;

    @Column(name = "events_conflict")
    private Integer conflicts;

    @Column(name = "events_needs_review")
    private Integer needsReview;

    /** Lines that could not be saved (they are also queued for review, with the error). */
    @Column(name = "events_failed")
    private Integer failed;

    /** Events that need no record, for a stated reason (a failed or cancelled payment). */
    @Column(name = "events_resolved")
    private Integer resolved;

    @Column(name = "outcome", length = 30)
    private String outcome;

    /** MATCHED / MISMATCHED against the statement's own stated totals; null when it states none. */
    @Column(name = "totals_check", length = 20)
    private String totalsCheck;

    @Column(name = "totals_detail", length = 500)
    private String totalsDetail;
}
