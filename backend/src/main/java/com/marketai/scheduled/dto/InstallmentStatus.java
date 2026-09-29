package com.marketai.scheduled.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder
public class InstallmentStatus {
    private LocalDate dueDate;
    /**
     * COMPLETED — a purchase of at least the scheduled amount was recorded near the due date;
     * PARTIAL — a purchase was recorded, but for less than the scheduled amount;
     * FAILED — the user (or a bounce advice) recorded that the debit failed;
     * MISSED — no purchase is recorded and nothing explains why;
     * PAUSED — the schedule was paused on that date, so nothing was due;
     * UPCOMING — not yet due.
     */
    private String status;
    /** What the schedule asked for on that date — the amount in force then, not today's. */
    private BigDecimal expectedAmount;
    private BigDecimal actualAmount; // set for COMPLETED/PARTIAL (from the matched purchase), else null
    private String note;             // e.g. the failure reason
}
