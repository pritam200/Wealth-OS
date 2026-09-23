package com.marketai.scheduled.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data @Builder
public class AmountChange {
    private LocalDateTime changedAt;
    private String field; // "amount" | "status"
    private String oldValue;
    private String newValue;
}
