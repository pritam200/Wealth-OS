package com.marketai.cashflow.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * One inflow/outflow landing on a given forecast day. {@code amount} is signed — positive for
 * a credit (income, SIP maturity), negative for a debit (expense run-rate, SIP contribution) —
 * so a caller can sum a day's events directly into the balance delta.
 */
@Data
@Builder
public class ProjectedEvent {

    private String label;
    private BigDecimal amount;

    /** INCOME | EXPENSE | SIP | FD_MATURITY | RD_MATURITY */
    private String type;

    /**
     * False for events backed by an explicit schedule/date the app already stores (SIP debit
     * date, FD/RD maturity date) — true for anything reasoned from historical averages
     * (recurring-income detection, expense run-rate), where the date and/or amount is a
     * best guess, not a fact on record. Mirrors the rest of the codebase's practice of
     * flagging uncertainty (data gaps, dataQuality) rather than presenting a guess as settled.
     */
    private boolean estimated;
}
