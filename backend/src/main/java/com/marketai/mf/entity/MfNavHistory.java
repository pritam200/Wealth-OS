package com.marketai.mf.entity;

import javax.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One published NAV for one scheme on one day, sourced from MFAPI.in (which republishes AMFI's
 * historical NAV archive). Mirrors {@link com.marketai.market.entity.PriceHistory} for equities.
 *
 * <p>The unique constraint on (scheme_code, date) is what makes the fetch idempotent: re-running
 * a fetch cannot duplicate a day even if the in-service filter is bypassed.
 */
@Entity
@Table(name = "mf_nav_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_mfnav_scheme_date",
                columnNames = {"scheme_code", "date"}),
        indexes = {
                @Index(name = "idx_mfnav_scheme", columnList = "scheme_code"),
                @Index(name = "idx_mfnav_scheme_date", columnList = "scheme_code, date")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfNavHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scheme_code", nullable = false, length = 20)
    private String schemeCode;

    @Column(nullable = false)
    private LocalDate date;

    /** AMFI publishes NAV to 4-5 decimals; scale 6 keeps it lossless. */
    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal nav;
}
