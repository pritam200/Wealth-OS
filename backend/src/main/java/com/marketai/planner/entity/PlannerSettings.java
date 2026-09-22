package com.marketai.planner.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One row per user: the PDF's "ABSOLUTE LIMIT" ceiling, separate from the sum of planned
 * categories (that sum is the "Planned Total" the PDF computes; the gap between the two is the
 * "Available Buffer"). */
@Entity
@Table(name = "planner_settings", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PlannerSettings {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "monthly_limit", precision = 12, scale = 2)
    private BigDecimal monthlyLimit;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
