package com.marketai.networth.entity;

import javax.persistence.*;
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
}
