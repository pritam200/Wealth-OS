package com.marketai.ledger.entity;

import com.marketai.auth.entity.User;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A movement of money between two things the user already owns — bank → mutual fund,
 * bank → broker, one bank account to another.
 *
 * This is the third kind of money event, distinct from an expense and from income:
 *   expense  → net worth falls
 *   income   → net worth rises
 *   transfer → net worth is unchanged; only the allocation between assets changes
 *
 * Booking a transfer as an expense (which is what a bank debit alert looks like) or as fresh
 * investment (which is what the AMC's confirmation looks like) is the classic double-count:
 * the same ₹50,000 leaves the bank and arrives in the fund, and if only one leg is recorded
 * the totals are wrong in both directions.
 *
 * `destinationType` is a string rather than an FK because the far side can be any asset class
 * (MF holding, stock portfolio, FD, another cash account); the amount is applied to the cash
 * side here, while the asset side is created by its own normal import path.
 */
@Entity
@Table(name = "ledger_transfers", indexes = {
    @Index(name = "idx_lt_user_date", columnList = "user_id, transfer_date"),
    @Index(name = "idx_lt_source", columnList = "source_account_id")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LedgerTransfer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)

    @EqualsAndHashCode.Include
    @ToString.Include    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Cash account the money left. Null when the source is outside tracked accounts. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private CashAccount sourceAccount;

    /** Cash account the money arrived in, for account-to-account moves. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private CashAccount destinationAccount;

    /** CASH_ACCOUNT | MUTUAL_FUND | STOCK | FD | RD | EPF | EXTERNAL */
    @Column(name = "destination_type", nullable = false, length = 20)
    private String destinationType;

    /** Free-text label for a non-cash destination, e.g. the fund or broker name. */
    @Column(name = "destination_ref", length = 200)
    private String destinationRef;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    @Column(length = 300)
    private String note;

    @Column(name = "source_email_id", length = 100)
    private String sourceEmailId;

    /** Whether the cash-side balance change has been applied, so re-running never double-applies. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean applied = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
