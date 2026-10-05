package com.marketai.dataplatform.domain;

public enum ConsentStatus {
    REQUESTED, PENDING_APPROVAL, APPROVED, REJECTED, REVOKED, EXPIRED, PAUSED, FAILED;

    public boolean usable() { return this == APPROVED; }
}
