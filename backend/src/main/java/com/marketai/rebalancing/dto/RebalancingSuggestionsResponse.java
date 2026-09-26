package com.marketai.rebalancing.dto;

import com.marketai.recommendation.dto.PortfolioContext;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Current-allocation concentration report — deliberately NOT a target-allocation rebalancer.
 * There is no user-defined target allocation anywhere in this codebase (checked: FinancialGoal
 * stores rupee targets and dates, never an asset mix), so "rebalance to target" would require
 * inventing a target-allocation model. Instead this surfaces what is already true of the
 * portfolio — concentration by holding and sector — and, only where a real tax-lot ledger exists,
 * the real tax cost of trimming the flagged position.
 */
@Data
@Builder
public class RebalancingSuggestionsResponse {
    private LocalDateTime generatedAt;
    private BigDecimal totalAssets;
    private List<AssetClassExposure> assetClassBreakdown;
    private List<PortfolioContext.Exposure> sectorBreakdown;
    private List<PortfolioContext.Flag> concentrationFlags;
    private List<TrimSuggestionDto> trimSuggestions;
    private List<String> dataGaps;
    private String scopeNote;
}
