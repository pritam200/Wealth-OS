package com.marketai.ledger.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder
public class CashAccountResponse {
    private Long id;
    private String name;
    private String bank;
    private String lastFour;
    private String accountType;
    private BigDecimal balance;
    private LocalDate asOf;
    private boolean active;
}
