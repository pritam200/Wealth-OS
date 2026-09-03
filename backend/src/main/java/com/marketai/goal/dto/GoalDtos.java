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
}
