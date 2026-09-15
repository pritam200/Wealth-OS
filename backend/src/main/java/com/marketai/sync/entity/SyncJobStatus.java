package com.marketai.sync.entity;

public enum SyncJobStatus {
    /** Waiting for a worker. */
    QUEUED,
    /** Claimed by a worker and in progress. */
    RUNNING,
    SUCCEEDED,
    /** Exhausted its retries. lastError explains why. */
    FAILED,
    CANCELLED
}
