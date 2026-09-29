package com.marketai.common.jobs;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** The last run of each background job, and its last failure — shown in the Reconciliation Center. */
@Entity
@Table(name = "scheduled_job_health")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ScheduledJobHealth {

    @Id
    @Column(name = "job_name", length = 80)
    private String jobName;

    @Column(name = "last_started_at")
    private LocalDateTime lastStartedAt;

    @Column(name = "last_finished_at")
    private LocalDateTime lastFinishedAt;

    /** SUCCEEDED, PARTIAL (some users/items failed) or FAILED. */
    @Column(name = "last_status", length = 20)
    private String lastStatus;

    @Column(name = "last_failure_at")
    private LocalDateTime lastFailureAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "consecutive_failures")
    private int consecutiveFailures;
}
