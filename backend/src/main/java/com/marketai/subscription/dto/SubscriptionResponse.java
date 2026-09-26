package com.marketai.subscription.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One recurring charge detected in the user's expense history. */
@Data
@Builder
public class SubscriptionResponse {
    private String merchant;
    private String category;
    private SubscriptionCadence cadence;
    /** Historical average charge — excludes the latest occurrence when it flags a price change. */
    private BigDecimal typicalAmount;
    private BigDecimal currentAmount;
    private LocalDate lastSeenDate;
    private int occurrenceCount;
    /** True when the most recent charge diverged from the historical average beyond tolerance —
     *  a signal to surface, never acted on automatically. */
    private boolean priceIncreased;
    private BigDecimal previousAmount;
}
