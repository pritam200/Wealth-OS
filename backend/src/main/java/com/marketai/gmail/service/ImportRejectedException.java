package com.marketai.gmail.service;

/**
 * A parsed transaction that cannot be booked as-is (incomplete figures, no holding to redeem
 * against, no type to route by). Thrown instead of returning quietly so the caller can route the
 * item to the review queue — a quiet return used to be counted as "imported" and the transaction
 * was then never seen again.
 */
public class ImportRejectedException extends Exception {
    public ImportRejectedException(String reason) {
        super(reason);
    }
}
