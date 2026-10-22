package com.marketai.card.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class CardStatementDtos {

    @Data @Builder
    public static class StatementResponse {
        private Long id;
        private LocalDate statementDate;
        private LocalDate dueDate;
        private BigDecimal totalDue;
        private BigDecimal minimumDue;
        private BigDecimal previousBalance;
        private Boolean arithmeticMismatch;
        private String arithmeticMismatchDetail;
        private String sourceEmailId;
        private BigDecimal creditLimit;
        private BigDecimal availableLimit;
        private String cardLastFour;
        /** Bank charges and unusual activity found on this statement, most serious first. */
        private List<com.marketai.card.service.CardStatementAnalyzer.Flag> flags;
        /** The statement's lines and how they tallied against expenses; null for a statement read before lines were kept. */
        private TallySummary tally;
    }

    @Data @Builder
    public static class TallySummary {
        private int lines;
        private int matched;
        private int added;
        private int missing;
        private int credits;
        private BigDecimal debitTotal;
        private BigDecimal creditTotal;
    }

    @Data @Builder
    public static class LineResponse {
        private Long id;
        private LocalDate txnDate;
        private String merchant;
        private BigDecimal amount;
        private boolean debit;
        private String currency;
        private String category;
        private String evidence;
        /** MATCHED | ADDED | MISSING | IGNORED | CREDIT */
        private String status;
        private Long expenseId;
        private LocalDate expenseDate;
        private String expenseDescription;
    }

    @Data @Builder
    public static class StatementDetail {
        private StatementResponse statement;
        private List<LineResponse> lines;
    }

    @Data @Builder
    public static class DetectedCardResponse {
        private Long id;
        private String issuer;
        private String lastFour;
        private String suggestedName;
        private int seenCount;
        private int pendingStatements;
        private java.time.LocalDateTime firstSeenAt;
        private java.time.LocalDateTime lastSeenAt;
        /** Saved cards that could be this one: same issuer, and no conflicting last four digits. */
        private List<Long> likelyCardIds;
    }

    /** What applying a statement to its card changed, in plain words; empty when the card already matched. */
    @Data @Builder
    public static class ApplyResult {
        private List<String> changes;
    }

    @Data @Builder
    public static class PaymentResponse {
        private Long id;
        private BigDecimal amount;
        private LocalDate paymentDate;
        private String referenceNumber;
        private String status;
        private String sourceEmailId;
    }

    public enum ReconciliationStatus { PAID, PARTIALLY_PAID, OUTSTANDING, OVERPAID }

    @Data @Builder
    public static class StatementReconciliation {
        private Long statementId;
        private LocalDate statementDate;
        private LocalDate dueDate;
        private BigDecimal totalDue;
        /** Sum of confirmed payments allocated to this statement by the waterfall below. */
        private BigDecimal amountApplied;
        /** totalDue − amountApplied. Negative means a credit (overpayment) sits on this statement. */
        private BigDecimal outstanding;
        private ReconciliationStatus status;
        private List<Long> matchedPaymentIds;
    }
}
