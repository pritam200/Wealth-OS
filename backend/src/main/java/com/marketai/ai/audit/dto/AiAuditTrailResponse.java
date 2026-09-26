package com.marketai.ai.audit.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class AiAuditTrailResponse {
    private Long id;
    private String task;
    private String provider;
    private String model;
    private String referenceId;
    private String systemInstruction;
    private String prompt;
    private String rawOutput;
    private BigDecimal confidence;
    private String status;
    private String note;
    private Long latencyMs;
    private LocalDateTime createdAt;
}
