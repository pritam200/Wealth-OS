package com.marketai.reconciliation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** When the checks last actually ran for a user, so "checked at" is that time and not the moment a page was opened. */
@Entity
@Table(name = "reconciliation_runs")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ReconciliationRun {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "last_run_at", nullable = false)
    private LocalDateTime lastRunAt;
}
