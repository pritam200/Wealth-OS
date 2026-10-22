package com.marketai.recommendation.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class MfRecommendationRequest {
    private String symbol;
    private String fundName;
    private LocalDate buyDate;
    private BigDecimal xirr;
    private BigDecimal investedValue;
    private BigDecimal currentValue;
    private BigDecimal quantity;
    /** AMFI scheme code when the holding is linked; resolved from the fund name otherwise. */
    private String schemeCode;
    // Total value of ALL mutual fund holdings in the portfolio (not just this one) —
    // used for the concentration/rebalance check. Cheap for the caller to supply
    // since the frontend already has every holding in memory; avoids a DB round-trip here.
    private BigDecimal totalMfPortfolioValue;
    // Set on the server from the user's own records, never taken from the client:
    // ACTIVE | PAUSED (a recorded SIP), INFERRED (monthly purchases in the ledger), NONE (lump sum).
    private String sipStatus;
    private BigDecimal sipAmount;
}
