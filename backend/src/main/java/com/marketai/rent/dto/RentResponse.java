package com.marketai.rent.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class RentResponse {
    /** Null for this month's instalment of a schedule that has nothing recorded yet. */
    private Long id;
    private Long scheduleId;
    private LocalDate month;
    private BigDecimal amount;
    private LocalDate paidDate;
    private String paidTo;
    private Long cashAccountId;
    private String paymentMethod;
    private String referenceId;
    private String note;
    private String sourceEmailId;
    private String status; // PAID | UPCOMING | OVERDUE | MISSED
    /** True when nothing was added because the payment was already recorded from email. */
    private boolean alreadyRecorded;
}
