package com.marketai.technical.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One indicator with everything needed to check it: formula, period, timeframe, as-of bar and sufficiency. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class IndicatorValue {
    private String key;
    private String name;
    /** Null when not computable; see {@code reason}. */
    private BigDecimal value;
    private String unit;
    private String formula;
    private Integer period;
    private String timeframe;
    private LocalDate asOf;
    private int barsRequired;
    private int barsAvailable;
    private boolean available;
    private String reason;
}
