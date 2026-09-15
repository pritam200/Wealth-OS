package com.marketai.ai.review.entity;

public enum ReviewStatus {
    /** Waiting for a human decision. */
    PENDING,
    /** Accepted as-is and imported. */
    ACCEPTED,
    /** Corrected by the user, then imported. */
    EDITED,
    /** Confirmed as not a real transaction — kept for audit, never imported. */
    REJECTED
}
