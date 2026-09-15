package com.marketai.ai.intel;

import com.marketai.gmail.parser.ParsedEmail;
import lombok.Builder;
import lombok.Data;

/**
 * Outcome of classifying one email.
 *
 * `outcome` is what the caller must branch on — never `confidence` directly, so the threshold
 * lives in one place.
 */
@Data @Builder
public class EmailIntelResult {

    public enum Outcome {
        /** Confident and bookable — a ParsedEmail is attached. */
        IMPORT,
        /** Understood but must not be auto-booked (transfer, switch, statement-only). */
        REVIEW_REQUIRED,
        /** Not a financial transaction at all — safe to skip without a review row. */
        NOT_A_TRANSACTION,
        /** The model was unavailable or returned unusable output. */
        UNRESOLVED
    }

    private Outcome outcome;
    private EmailIntelType type;
    private Double confidence;
    private String reasoning;
    private String evidence;

    /** Present only when outcome == IMPORT. */
    private ParsedEmail parsed;

    /** Why this needs a human, when outcome == REVIEW_REQUIRED / UNRESOLVED. */
    private String reviewReason;

    /** The model's extracted fields as raw JSON, kept verbatim for the review UI. */
    private String extractedFieldsJson;
}
