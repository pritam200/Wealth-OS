package com.marketai.rebalancing.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A single read-only "consider trimming this" suggestion, raised by a concentration flag that
 * {@code PortfolioContextService} already computed from real holdings. Never an executed trade —
 * the caller only ever sees a size and its real tax cost, decided against actual lots.
 */
@Data
@Builder
public class TrimSuggestionDto {
    private String triggerType;      // SINGLE_STOCK | SECTOR — mirrors PortfolioContext.Flag.type
    private String triggerLabel;     // the stock or sector name that triggered this
    private String symbol;
    private String name;
    private BigDecimal currentValue;
    private Double currentPercent;   // of total assets or of equity, whichever breached
    private String basis;            // which guideline this percent is measured against
    private BigDecimal suggestedSellUnits;
    private BigDecimal suggestedSellValue;
    /** Why this specific holding, not another one in the same sector, was picked to trim. */
    private String selectionReason;
    private String reason;
    /** Null when a tax figure could not be honestly computed (e.g. no live price, no open lots). */
    private TaxImpactDto taxImpact;
    private String taxImpactGap;
}
