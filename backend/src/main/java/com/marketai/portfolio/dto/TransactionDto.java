package com.marketai.portfolio.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class TransactionDto {
    private Long id;
    private Long holdingId;
    private String symbol;
    private String fundName;
    private String type;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal totalAmount;
    private BigDecimal charges;
    private LocalDate transactionDate;
    private String notes;
    private String broker;
    private String folio;
    private LocalDateTime createdAt;
}
