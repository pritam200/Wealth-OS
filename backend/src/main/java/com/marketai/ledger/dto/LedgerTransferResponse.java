package com.marketai.ledger.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder
public class LedgerTransferResponse {
    private Long id;
    private Long sourceAccountId;
    private String sourceAccountName;
    private Long destinationAccountId;
    private String destinationAccountName;
    private String destinationType;
    private String destinationRef;
    private BigDecimal amount;
    private LocalDate transferDate;
    private String note;
    private boolean applied;
    private LocalDateTime createdAt;
}
