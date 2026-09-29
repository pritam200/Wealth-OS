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
    /** Groups left alone because the rows share an identical trade — merging would count that
     *  trade twice. Each needs the duplicate trade removed by hand first. */
    private List<String> skipped;

    @Data
    @Builder
    public static class MergedGroup {
        private String symbol;
        private Long keptHoldingId;
        private List<String> removedHoldingIds;
    }
}
