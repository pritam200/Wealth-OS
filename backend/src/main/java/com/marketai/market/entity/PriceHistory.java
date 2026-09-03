package com.marketai.market.entity;

import javax.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "price_history", indexes = {
        @Index(name = "idx_ph_symbol_date", columnList = "symbol, date"),
        @Index(name = "idx_ph_date", columnList = "date")
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

    @Column(nullable = false)
    private LocalDate date;

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
