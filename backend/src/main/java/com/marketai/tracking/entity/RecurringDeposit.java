package com.marketai.tracking.entity;

import com.marketai.auth.entity.User;
import lombok.*;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "recurring_deposits")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RecurringDeposit {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String bank;

    @Column(name = "monthly_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyAmount;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal rate;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    // ACTIVE | MATURED (tenure complete, not yet closed/renewed) | CLOSED (withdrawn, not
    // renewed) | MATURED_RENEWED (matured and rolled into a new RD — see renewedToId).
    // Net-worth totals must exclude CLOSED and MATURED_RENEWED for the same reason as
    // FixedDeposit: the money either left the account or is now counted via the successor RD.
    @Column(length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "closed_date")
    private LocalDate closedDate;

    // Actual amount received on closure (may differ from the projected corpus).
    @Column(name = "maturity_amount", precision = 15, scale = 2)
    private BigDecimal maturityAmount;

    // Set on the OLD rd when a later RD is detected as its renewal (mirrors
    // FixedDeposit.renewedToId/renewedFromId — see TrackingService.detectAndLinkRdRenewal).
    @Column(name = "renewed_to_id")
    private Long renewedToId;

    @Column(name = "renewed_from_id")
    private Long renewedFromId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Derived, not stored — an RD's maturity date is always start date + tenure in months. */
    @Transient
    public LocalDate getMaturityDate() {
        return startDate != null ? startDate.plusMonths(tenureMonths) : null;
    }
}
