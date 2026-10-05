package com.marketai.dataplatform.domain;

public enum ProcessingStatus {
    RECEIVED, VALIDATED, NORMALIZED, PROCESSED,
    /** The same payload was already processed; nothing was created. */
    DUPLICATE_PAYLOAD,
    REJECTED, FAILED
}
