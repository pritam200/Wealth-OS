package com.marketai.ledger.entity;

import com.marketai.auth.entity.User;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A bank or cash account the user holds.
 *
 * Why this exists: net worth was previously assets = stocks + MF + FD + RD + EPF + other,
 * with no cash side at all. That made an internal transfer arithmetically impossible to
 * represent — moving ₹50,000 from a bank account into a mutual fund raised the MF value by
 * ₹50,000 while nothing decreased, so net worth grew by the full transfer amount. Tracking the
 * cash side is what lets a transfer net to zero.
 *
 * `balance` is the authoritative figure and is only ever changed by applying a
 * {@link LedgerTransfer} or by an explicit user correction — never inferred from an email.
 */
@Entity
@Table(name = "cash_accounts", indexes = {
    @Index(name = "idx_ca_user", columnList = "user_id")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CashAccount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)

    @EqualsAndHashCode.Include
    @ToString.Include    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 120)
    private String name;            // "HDFC Savings", "Cash in hand"

    @Column(length = 80)
    private String bank;

    @Column(name = "last_four", length = 4)
    private String lastFour;

    /** SAVINGS | CURRENT | WALLET | CASH */
    @Column(name = "account_type", length = 20)
    @Builder.Default
    private String accountType = "SAVINGS";

    @Column(nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    /**
     * Optimistic lock on the balance.
     *
     * <p>Every balance change is a read-modify-write in {@code LedgerTransferService}. With no
     * version, a Gmail sync worker and a user-initiated transfer debiting the same account
     * concurrently both read ₹1,00,000, both write ₹70,000, and ₹30,000 of outflow is lost
     * silently — the balance is permanently wrong with nothing recorded anywhere. The version
     * turns that into an {@code OptimisticLockException} the caller can retry.
     */
    @Version
    private Long version;

    /** When the balance was last reconciled against a statement. */
    @Column(name = "as_of")
    private LocalDate asOf;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
