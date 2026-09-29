package com.marketai.networth.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "net_worth_snapshots",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "snapshot_date"}),
    indexes = @Index(name = "idx_nw_user", columnList = "user_id"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class NetWorthSnapshot {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "total_assets", precision = 18, scale = 2)
    private BigDecimal totalAssets;

    @Column(name = "net_worth", precision = 18, scale = 2)
    private BigDecimal netWorth;

    @Column(name = "total_liabilities", precision = 18, scale = 2)
    private BigDecimal totalLiabilities;

    /** FULL | PARTIAL, as computed with the figure — a trend point built on cost-valued or
     *  missing inputs stays marked as such. Null on snapshots taken before this was kept. */
    @Column(name = "data_quality", length = 16)
    private String dataQuality;

    /** The named gaps behind a PARTIAL figure, newline-separated. */
    @Column(name = "data_gaps", columnDefinition = "TEXT")
    private String dataGaps;
}
