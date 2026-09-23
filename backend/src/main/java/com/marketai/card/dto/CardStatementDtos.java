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
