package com.marketai.technical.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Volume measures over bars that report volume; all null when the feed has none (e.g. some indices). */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class VolumeProfile {
    private Long latestVolume;
    /** Mean of the 20 sessions before the latest. */
    private BigDecimal averageVolume20;
    /** Latest ÷ 20-session average. */
    private BigDecimal relativeVolume;
    /** 20-session average ÷ 50-session average: RISING above 1.1, FALLING below 0.9, else FLAT. */
    private String volumeTrend;
    private BigDecimal volumeTrendRatio;
    /** Latest volume more than 2 standard deviations above the 20-session mean. */
    private Boolean unusual;
    private BigDecimal zScore;
    private int barsWithVolume;
    private String reason;
}
