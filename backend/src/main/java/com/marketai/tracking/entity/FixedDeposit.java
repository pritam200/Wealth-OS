package com.marketai.tracking.entity;

import com.marketai.auth.entity.User;
import lombok.*;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "fixed_deposits")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class FixedDeposit {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String bank;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal principal;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal rate;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String compounding = "quarterly"; // quarterly | monthly | annually

    @Column(name = "auto_renew")
    @Builder.Default
    private boolean autoRenew = false;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    // ACTIVE | CLOSED (withdrawn, not renewed) | MATURED_RENEWED (matured and rolled into a
    // new FD — see renewedToId). Net-worth totals must exclude both CLOSED and
    // MATURED_RENEWED: the money either left the account (CLOSED) or is now counted via the
    // successor FD (MATURED_RENEWED) — counting it here too would double-count it.
    @Column(length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "maturity_amount", precision = 15, scale = 2)
    private BigDecimal maturityAmount;

    @Column(name = "closed_date")
    private LocalDate closedDate;

    // Set on the OLD fd when a later FD is detected as its renewal (this fd's id appears as
    // that new fd's renewedFromId). Null unless status == MATURED_RENEWED.
    @Column(name = "renewed_to_id")
    private Long renewedToId;

    // Set on the NEW fd when it was detected as the renewal successor of an earlier matured
    // FD (see TrackingService.detectAndLinkRenewal). Null for a fd opened from scratch.
    @Column(name = "renewed_from_id")
    private Long renewedFromId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
