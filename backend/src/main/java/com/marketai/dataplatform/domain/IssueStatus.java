package com.marketai.dataplatform.domain;

public enum IssueStatus {
    OPEN, IN_REVIEW, RESOLVED, IGNORED,
    /** Closed by the system with an explanation (for example a duplicate merged by an exact reference). */
    AUTO_RESOLVED;

    public boolean open() { return this == OPEN || this == IN_REVIEW; }
}
