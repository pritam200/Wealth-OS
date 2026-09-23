package com.marketai.scheduled.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the merged "Scheduled Investments" view (spec §4/§16): RecurringInvestment
 * (SIP/PPF/NPS/STOCK_SIP/ETF_SIP/BROKER_RECURRING) and RecurringDeposit (RD) each map onto
 * this same shape. {@code sourceKind}/{@code sourceId} identify which underlying record this
 * came from, so the frontend can route an edit/pause/delete action to the right endpoint —
 * this DTO itself is read-only and never persisted.
 */
@Data @Builder
public class ScheduledInvestmentSummary {
    private String sourceKind; // "RECURRING_INVESTMENT" | "RECURRING_DEPOSIT"
    private Long sourceId;
    private String investmentType; // RI type name, or "RD"
    private String label;
    private BigDecimal amount;
    private String frequency; // "Monthly" for all current schedule types
    private Integer dueDayOfMonth; // derived from startDate; null when not applicable
    private Long sourceAccountId; // null for RD (RecurringDeposit has no cash-account link yet)
    private String destination; // linkedSymbol (RI) or bank (RD)
    private String status;
    private LocalDate startDate;
}
