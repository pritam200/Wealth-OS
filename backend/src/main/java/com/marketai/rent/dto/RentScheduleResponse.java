package com.marketai.rent.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class RentScheduleResponse {
    private Long id;
    private BigDecimal amount;
    private Integer dueDayOfMonth;
    private String paidTo;
    private Long cashAccountId;
    private String paymentMethod;
    private boolean active;
}
