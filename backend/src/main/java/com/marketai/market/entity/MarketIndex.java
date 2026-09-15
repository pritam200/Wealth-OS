package com.marketai.market.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "market_indices")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String symbol;

    @Column(nullable = false, length = 100)
    private String name;

    // See OtherAsset: `value` is reserved in H2, so the identifier is quoted via Hibernate's
    // portable backtick syntax. Column name unchanged.
    @Column(name = "`value`", precision = 18, scale = 2)
    private BigDecimal value;

    @Column(precision = 18, scale = 2)
    private BigDecimal change;

    @Column(precision = 8, scale = 4)
    private BigDecimal changePercent;

    @Column(precision = 18, scale = 2)
    private BigDecimal open;

    @Column(precision = 18, scale = 2)
    private BigDecimal high;

    @Column(precision = 18, scale = 2)
    private BigDecimal low;

    @Column(precision = 18, scale = 2)
    private BigDecimal previousClose;

    private LocalDateTime lastUpdated;
}
