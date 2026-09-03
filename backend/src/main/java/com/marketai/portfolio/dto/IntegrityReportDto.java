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
        // UNVERIFIABLE_NAME | DUPLICATE_FOLIO | DUPLICATE_DISPLAY_NAME | DUPLICATE_SYMBOL |
        // MISMATCHED_TICKER | UNVERIFIABLE_SYMBOL
        private String type;
        private String description;
        private BigDecimal currentValue;
        // MISMATCHED_TICKER only — the confirmed-correct symbol this holding should be merged
        // into, resolved once by checkIntegrity and reused as-is by fixMismatchedTickers so
        // the two never risk disagreeing on re-derivation.
        private String resolvedSymbol;
    }
}
