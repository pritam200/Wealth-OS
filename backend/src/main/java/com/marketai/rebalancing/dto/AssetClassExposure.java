package com.marketai.rebalancing.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** One slice of the current asset-class mix — never a target, only what is actually held. */
@Data
@Builder
public class AssetClassExposure {
    private String label;      // Equity | Mutual funds | Debt | Gold | Cash | Other
    private BigDecimal value;
    private Double percentOfTotalAssets;
}
