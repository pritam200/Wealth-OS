package com.marketai.tracking.entity;

import com.marketai.auth.entity.User;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "insurance_policies")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class InsurancePolicy {

    public enum PolicyType { TERM, HEALTH, MOTOR, OTHER }
    public enum PremiumFrequency { MONTHLY, QUARTERLY, ANNUAL }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PolicyType policyType;

    @Column(nullable = false, length = 200)
    private String insurer;

    @Column(name = "policy_number", length = 100)
    private String policyNumber;

    @Column(name = "sum_assured", precision = 15, scale = 2)
    private BigDecimal sumAssured;

    @Column(name = "premium_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal premiumAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "premium_frequency", nullable = false, length = 20)
    @Builder.Default
    private PremiumFrequency premiumFrequency = PremiumFrequency.ANNUAL;

    @Column(name = "next_premium_due_date")
    private LocalDate nextPremiumDueDate;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(length = 500)
    private String notes;

    // ACTIVE | LAPSED | CLOSED — mirrors FD/RD lifecycle status so a policy that's no longer
    // paid or has ended can be excluded from reminders without deleting the record.
    @Column(length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Days a missed premium stays "overdue" before the next one is assumed to be what's due. */
    public static final int PREMIUM_GRACE_DAYS = 30;

    /**
     * The premium actually due as of {@code today}. The stored date is only updated when the user
     * edits the policy, so without rolling it forward by the premium frequency it would read as
     * overdue forever after the first payment. Null when no date is set or the policy has ended.
     */
    public LocalDate nextDueDateAsOf(LocalDate today) {
        if (nextPremiumDueDate == null) return null;
        if (endDate != null && endDate.isBefore(today)) return null;
        int months = switch (premiumFrequency == null ? PremiumFrequency.ANNUAL : premiumFrequency) {
            case MONTHLY -> 1;
            case QUARTERLY -> 3;
            case ANNUAL -> 12;
        };
        LocalDate graceStart = today.minusDays(PREMIUM_GRACE_DAYS);
        LocalDate due = nextPremiumDueDate;
        // Step from the stored anchor (not the previous step) so a 31st doesn't drift to the 28th.
        for (int k = 1; due.isBefore(graceStart); k++) due = nextPremiumDueDate.plusMonths((long) k * months);
        if (endDate != null && due.isAfter(endDate)) return null;
        return due;
    }
}
