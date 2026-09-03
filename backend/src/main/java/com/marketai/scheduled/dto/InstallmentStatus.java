package com.marketai.scheduled.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder
public class InstallmentStatus {
    private LocalDate dueDate;
    private String status; // COMPLETED | MISSED | UPCOMING
    private BigDecimal actualAmount; // set only for COMPLETED (from matched Transaction), else null
}
