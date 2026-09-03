package com.marketai.redemption.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * A staged reinvestment plan for redeemed cash. Rule-based, grounded in the same ATR/volatility
 * math the forecast engine already uses for the market — NOT continuous live AI monitoring
 * (that's out of scope for this rule-based v1; disclosed via `basis`).
 */
@Data @Builder
public class DeploymentPlan {
    private List<Tranche> tranches;
    private List<String> suitableFunds; // limited to funds already in the user's portfolio
    private String basis;

    @Data @Builder
    public static class Tranche {
        private String label;          // "Invest now" | "After a correction" | "Monthly SIP over 6 months"
        private BigDecimal amount;
        private Double percentOfTotal;
        private String trigger;        // e.g. "Nifty 50 below 23,850 (a 5% pullback from today)"
    }
}
