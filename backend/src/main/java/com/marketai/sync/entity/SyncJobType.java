package com.marketai.sync.entity;

public enum SyncJobType {
    /** Full window scan — the historical behaviour. Used for first connect and as the
     *  recovery path when incremental sync can't be trusted. */
    GMAIL_FULL_SYNC,
    /** Delta since a known historyId. Cheap; the normal path once a baseline exists. */
    GMAIL_INCREMENTAL_SYNC,
    /** Re-attempt of emails previously left in a FAILED state. */
    GMAIL_RETRY_FAILED
}
