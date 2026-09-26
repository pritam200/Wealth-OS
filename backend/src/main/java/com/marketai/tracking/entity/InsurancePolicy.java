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
}
