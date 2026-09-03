package com.marketai.portfolio.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class IntegrityReportDto {
    private int totalHoldings;
    private int issueCount;
    private List<Issue> issues;

    @Data
    @Builder
    public static class Issue {
        private Long holdingId;
        private String symbol;
        private String name;
        private String type;        // UNVERIFIABLE_NAME | DUPLICATE_FOLIO | DUPLICATE_DISPLAY_NAME
        private String description;
        private BigDecimal currentValue;
    }
}
