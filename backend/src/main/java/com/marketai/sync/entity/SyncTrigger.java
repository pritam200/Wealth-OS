package com.marketai.sync.entity;

public enum SyncTrigger {
    /** The user pressed sync. */
    MANUAL,
    /** The safety-net timer. */
    SCHEDULED,
    /** A Gmail push notification arrived. */
    PUSH,
    /** Requeued after a worker died mid-job. */
    RECOVERY
}
