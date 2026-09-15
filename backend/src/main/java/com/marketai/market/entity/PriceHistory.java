package com.marketai.market.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "price_history", indexes = {
        @Index(name = "idx_ph_symbol_date", columnList = "symbol, date"),
        @Index(name = "idx_ph_date", columnList = "date"),
        @Index(name = "idx_ph_symbol_interval_bar", columnList = "symbol, bar_interval, bar_start")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    /**
     * Calendar date of the bar. Retained as-is so every existing daily query keeps working;
     * for intraday bars this is the date component of {@link #barStart}.
     */
    @Column(nullable = false)
    private LocalDate date;

    /**
     * Exact opening instant of the bar. Required for intraday: a LocalDate alone cannot
     * distinguish the 09:30 and 13:15 fifteen-minute bars of the same session.
     *
     * Null on legacy daily rows written before this column existed.
     */
    @Column(name = "bar_start")
    private java.time.LocalDateTime barStart;

    /**
     * Bar size: "15m", "60m", "4h" or "1d". Defaults to daily so pre-existing rows keep their
     * original meaning rather than becoming ambiguous.
     *
     * Named bar_interval because INTERVAL is a reserved word in PostgreSQL.
     */
    @Column(name = "bar_interval", length = 5)
    @Builder.Default
    private String interval = "1d";

    @Column(precision = 18, scale = 2)
    private BigDecimal open;

    @Column(precision = 18, scale = 2)
    private BigDecimal high;

    @Column(precision = 18, scale = 2)
    private BigDecimal low;

    @Column(precision = 18, scale = 2)
    private BigDecimal close;

    @Column(precision = 18, scale = 2)
    private BigDecimal adjClose;

    private Long volume;
}
