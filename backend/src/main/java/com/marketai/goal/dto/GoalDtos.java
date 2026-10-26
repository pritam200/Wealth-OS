package com.marketai.goal.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public class GoalDtos {

    /** The whole goal: an update replaces every field, so a blank target date clears it. */
    @Data
    public static class GoalRequest {
        @NotBlank(message = "Give the goal a name")
        @Size(max = 120, message = "Keep the name under 120 characters")
        private String name;
        @Size(max = 40)
        private String category;
        @NotNull(message = "Set a target amount")
        @Positive(message = "The target amount must be more than zero")
        private BigDecimal targetAmount;
        @PositiveOrZero(message = "Saved so far can't be negative")
        private BigDecimal currentSaved;
        @PositiveOrZero(message = "The monthly amount can't be negative")
        private BigDecimal monthlyContribution;
        @DecimalMin(value = "0", message = "Expected return can't be negative")
        @DecimalMax(value = "50", message = "Expected return above 50% a year isn't realistic")
        private BigDecimal expectedReturn;
        @DecimalMin(value = "0", message = "Inflation can't be negative")
        @DecimalMax(value = "30", message = "Inflation above 30% a year isn't realistic")
        private BigDecimal inflationRate;
        private LocalDate targetDate;
        private Set<Long> linkedHoldingIds;
        private Set<Long> linkedSipIds;
    }

    /** A holding or SIP the user can count toward a goal. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LinkOption {
        private Long id;
        private String label;
        private String detail;        // portfolio name for a holding, status for a SIP
        private BigDecimal amount;    // current value for a holding, monthly amount for a SIP
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LinkOptions {
        private List<LinkOption> holdings;
        private List<LinkOption> sips;
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
        private BigDecimal inflationRate;
        private Set<Long> linkedHoldingIds;
        private Set<Long> linkedSipIds;
        // computed
        private BigDecimal linkedValue;        // current value of the linked holdings, part of totalSaved
        private BigDecimal linkedMonthly;      // monthly amount of the active linked SIPs, part of totalMonthly
        private BigDecimal totalSaved;         // currentSaved + linkedValue
        private BigDecimal totalMonthly;       // monthlyContribution + linkedMonthly
        private BigDecimal inflatedTarget;     // targetAmount grown by inflation to the target date
        private BigDecimal shortfallAmount;    // inflatedTarget - projectedValue, when short
        private double progressPercent;      // totalSaved / inflatedTarget
        private BigDecimal projectedValue;   // future value at target date given SIP + return
        private boolean onTrack;             // projectedValue >= target
        private Integer monthsToTarget;
        private BigDecimal requiredMonthly;  // SIP needed to exactly hit target by date
        private String status;               // ON_TRACK | SHORTFALL | ACHIEVED | OVERDUE
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
        private boolean scenarioNeverCompletes; // baseline reaches the target within 50 years, the scenario doesn't
        private Integer completionDelayMonths;  // scenario.completionMonth - baseline.completionMonth; positive = scenario finishes later. Null if either never completes.
        private BigDecimal projectedValueDelta; // scenario.projectedValue - baseline.projectedValue
    }
}
