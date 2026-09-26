package com.marketai.goal.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public class GoalDtos {

    @Data
    public static class GoalRequest {
        private String name;
        private String category;
        private BigDecimal targetAmount;
        private BigDecimal currentSaved;
        private BigDecimal monthlyContribution;
        private BigDecimal expectedReturn;
        private LocalDate targetDate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class GoalResponse {
        private Long id;
        private String name;
        private String category;
        private BigDecimal targetAmount;
        private BigDecimal currentSaved;
        private BigDecimal monthlyContribution;
        private BigDecimal expectedReturn;
        private LocalDate targetDate;
        // computed
        private double progressPercent;      // currentSaved / target
        private BigDecimal projectedValue;   // future value at target date given SIP + return
        private boolean onTrack;             // projectedValue >= target
        private Integer monthsToTarget;
        private BigDecimal requiredMonthly;  // SIP needed to exactly hit target by date
        private String status;               // ON_TRACK | SHORTFALL | ACHIEVED
    }

    /** Hypothetical adjustment for a what-if simulation — never persisted against the real goal/SIP. */
    @Data
    public static class WhatIfRequest {
        private String adjustmentType;     // PAUSE_SIP | LUMP_SUM
        private Integer startMonth;        // months from now the adjustment takes effect (1-based); defaults to 1
        private Integer pauseMonths;       // PAUSE_SIP: number of consecutive months the SIP contribution is skipped
        private BigDecimal lumpSumAmount;  // LUMP_SUM: one-time amount credited at startMonth
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WhatIfProjection {
        private BigDecimal projectedValue;
        private Integer completionMonth;   // months from now until target is reached; null if not reached in the simulation window
        private LocalDate completionDate;
        private boolean onTrack;
        private BigDecimal shortfall;      // max(0, target - projectedValue)
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class WhatIfResponse {
        private Long goalId;
        private WhatIfProjection baseline;
        private WhatIfProjection scenario;
        private Integer completionDelayMonths;  // scenario.completionMonth - baseline.completionMonth; positive = scenario finishes later. Null if either never completes.
        private BigDecimal projectedValueDelta; // scenario.projectedValue - baseline.projectedValue
    }
}
