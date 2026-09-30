package com.marketai.technical.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A support or resistance level and the market structure it comes from. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PriceLevel {
    private BigDecimal price;
    /** SWING_CLUSTER, 52W_HIGH, 52W_LOW, SMA50, SMA200, GAP */
    private String source;
    /** Swing highs/lows within the cluster tolerance; 0 for moving averages and gaps. */
    private int touches;
    private LocalDate lastTouched;
    /** Distance from the current price, percent (negative = below). */
    private BigDecimal distancePct;
    private String reason;
}
