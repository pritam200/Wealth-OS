package com.marketai.scheduled.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Partial update — only non-null fields are applied. Covers spec §18's "edit, pause,
 * resume, change amount, change bank, change destination": pause/resume is just a status
 * change ({@code status=PAUSED}/{@code status=ACTIVE}) through this same endpoint.
 */
@Data
public class RecurringInvestmentUpdateRequest {
    private BigDecimal amount;
    private String status; // ACTIVE | PAUSED | COMPLETED
    private Long sourceAccountId;
    private String linkedSymbol;
    private String label;
}
