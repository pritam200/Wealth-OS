package com.marketai.portfolio.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MergeSummaryDto {
    private int groupsMerged;
    private int holdingsMerged;
    private List<MergedGroup> groups;

    @Data
    @Builder
    public static class MergedGroup {
        private String symbol;
        private Long keptHoldingId;
        private List<String> removedHoldingIds;
    }
}
