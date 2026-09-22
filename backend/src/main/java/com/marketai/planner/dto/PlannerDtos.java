package com.marketai.planner.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class PlannerDtos {

    @Data @Builder
    public static class CategoryResponse {
        private Long id;
        private String key;
        private String name;
        private String groupName;
        private BigDecimal plannedAmount;
        private Integer sortOrder;
        private String keywords;
        private boolean fallback;
        private String linkedSinkingFundName;
        private boolean active;
    }

    @Data
    public static class CategoryRequest {
        private String name;
        private String groupName;
        private BigDecimal plannedAmount;
        private Integer sortOrder;
        private String keywords;
        private Boolean active;
    }

    @Data @Builder
    public static class SettingsResponse {
        private BigDecimal monthlyLimit;
    }

    @Data
    public static class SettingsRequest {
        private BigDecimal monthlyLimit;
    }

    /** One line item inside a category box — a transaction the classifier or the user's own
     *  override bucketed into this category. */
    @Data @Builder
    public static class PlanTransaction {
        private Long expenseId;
        private LocalDate date;
        private String merchant;
        private String description;
        private BigDecimal amount;
        private String paymentMethod;
        private String sourceEmailId;
        /** What the deterministic classifier would assign right now — always recomputed, never
         *  stored, so editing a category's keywords immediately changes what this says. */
        private String aiCategoryKey;
        private String finalCategoryKey;
        private boolean overridden;
    }

    /** One PDF-style section box: planned/actual/remaining plus the transactions inside it. */
    @Data @Builder
    public static class CategoryPlanLine {
        private String key;
        private String name;
        private String groupName;
        private BigDecimal planned;
        private BigDecimal actual;
        private BigDecimal remaining;
        private boolean linkedToSinkingFund;
        private List<PlanTransaction> transactions;
    }

    @Data @Builder
    public static class GroupSummary {
        private String groupName;
        private BigDecimal planned;
        private BigDecimal actual;
        private BigDecimal difference;
    }

    /** The full payload for the tab's top section + all category boxes + month-end group rollup. */
    @Data @Builder
    public static class MonthlyPlanResponse {
        private int year;
        private int month;
        private BigDecimal monthlyLimit;
        private BigDecimal plannedTotal;
        private BigDecimal actualTotal;
        private BigDecimal remaining;
        private BigDecimal buffer;
        /** WITHIN_PLAN / NEAR_LIMIT / LIMIT_REACHED / OVER_LIMIT — see PlannerService for the rule. */
        private String status;
        private List<CategoryPlanLine> categories;
        private List<GroupSummary> groupSummaries;
        private String biggestExpenseCategory;
    }

    @Data @Builder
    public static class ReflectionResponse {
        private String yearMonth;
        private String biggestExpenseNote;
        private String overspendNote;
        private String underspendNote;
        private String oneOffNote;
        private String adjustmentsNote;
        private String finalStatus;
        private String suggestedStatus;
    }

    @Data
    public static class ReflectionRequest {
        private String biggestExpenseNote;
        private String overspendNote;
        private String underspendNote;
        private String oneOffNote;
        private String adjustmentsNote;
        private String finalStatus;
    }

    @Data @Builder
    public static class MoveExpenseRequest {
        private String planCategoryKey;
    }

    @Data @Builder
    public static class SinkingFundResponse {
        private Long id;
        private String name;
        private BigDecimal monthlyPlanned;
        private BigDecimal annualTarget;
        private boolean active;
    }

    @Data
    public static class SinkingFundRequest {
        private String name;
        private BigDecimal monthlyPlanned;
        private BigDecimal annualTarget;
        private Boolean active;
    }

    @Data @Builder
    public static class SinkingFundLedgerRow {
        private String yearMonth;
        private BigDecimal planned;
        private BigDecimal added;
        private BigDecimal used;
        private BigDecimal balance;
    }

    @Data @Builder
    public static class SinkingFundLedgerResponse {
        private Long fundId;
        private String fundName;
        private int year;
        private List<SinkingFundLedgerRow> rows;
        private BigDecimal totalPlanned;
        private BigDecimal totalAdded;
        private BigDecimal totalUsed;
        private BigDecimal endingBalance;
    }

    @Data
    public static class SinkingFundEntryRequest {
        private String yearMonth;
        private BigDecimal added;
        private BigDecimal used;
    }
}
