package com.marketai.sync.entity;

public enum SyncJobStatus {
    /** Waiting for a worker. */
    QUEUED,
    /** Claimed by a worker and in progress. */
    RUNNING,
    /** Every message was read and booked, or deliberately skipped. */
    SUCCEEDED,
    /** Finished, but some messages failed; they are retried by the backlog job. */
    PARTIAL_SUCCESS,
    /** Finished with nothing failed, but something needs the user (a PDF awaiting its
     *  password, items held for review) before the mailbox is fully accounted for. */
    RECONCILIATION_REQUIRED,
    /** Exhausted its retries. lastError explains why. */
    FAILED,
    CANCELLED
}
