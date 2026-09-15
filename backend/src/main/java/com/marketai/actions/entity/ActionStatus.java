package com.marketai.actions.entity;

/**
 * Lifecycle of a recommended action as the user works through their queue.
 *
 * EXECUTED records only that the user says they acted — it never moves money or changes a
 * holding. The portfolio changes solely when a real Transaction is booked through the
 * portfolio endpoints, which is what keeps recommendations and the ledger independent.
 */
public enum ActionStatus {
    PENDING,
    EXECUTED,
    SKIPPED,
    SNOOZED
}
