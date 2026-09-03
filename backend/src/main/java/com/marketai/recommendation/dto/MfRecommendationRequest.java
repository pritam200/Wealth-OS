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
    // Total value of ALL mutual fund holdings in the portfolio (not just this one) —
    // used for the concentration/rebalance check. Cheap for the caller to supply
    // since the frontend already has every holding in memory; avoids a DB round-trip here.
    private BigDecimal totalMfPortfolioValue;
}
