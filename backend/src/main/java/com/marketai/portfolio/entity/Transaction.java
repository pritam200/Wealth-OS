package com.marketai.portfolio.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_txn_holding", columnList = "holding_id"),
        @Index(name = "idx_txn_date", columnList = "transaction_date")
})
// Lombok's @Data generates equals, hashCode and toString over *every* field, including JPA
// relations. On an entity with a bidirectional mapping that recurses forever:
// Holding.hashCode() reads its portfolio, Portfolio.hashCode() reads its holdings list, which
// reads this holding again — a StackOverflowError, reproduced and confirmed before this fix.
// The same recursion applies to toString(), so simply logging an entity crashed the thread.
//
// On lazy relations it is also a LazyInitializationException (or a silent N+1) as soon as
// equals/hashCode is called outside a session.
//
// Identity is therefore the primary key alone, which is what JPA semantics actually mean by
// "the same row", and relations are excluded from toString.
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)

    @EqualsAndHashCode.Include
    @ToString.Include    private Long id;

    /** Where this record came from (source email, extraction method). */
    @Embedded
    private com.marketai.common.ledger.Provenance provenance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holding_id", nullable = false)
    private Holding holding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    /** Per-unit price or NAV — four decimals, as NAVs are published. */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal price;

    @Column(precision = 18, scale = 2)
    private BigDecimal charges;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    /** SPLIT only: each share held before becomes {@code ratioTo / ratioFrom} shares — a 1:5
     *  split is from 1, to 5. Lots keep their purchase dates; cost per share is divided. */
    @Column(name = "ratio_from", precision = 12, scale = 4)
    private BigDecimal ratioFrom;

    @Column(name = "ratio_to", precision = 12, scale = 4)
    private BigDecimal ratioTo;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Transient
    public BigDecimal getTotalAmount() {
        if (type == TransactionType.SPLIT || type == TransactionType.BONUS) return BigDecimal.ZERO;
        BigDecimal base = price.multiply(quantity);
        return type == TransactionType.BUY
                ? base.add(charges == null ? BigDecimal.ZERO : charges)
                : base.subtract(charges == null ? BigDecimal.ZERO : charges);
    }

    /** Whether this row adds units that are then held as a lot: a purchase, or bonus shares. */
    @Transient
    public boolean addsUnits() {
        return type == TransactionType.BUY || type == TransactionType.BONUS;
    }

    /** The split multiplier (to / from), or null when this is not a usable split row. */
    @Transient
    public BigDecimal splitMultiplier() {
        if (type != TransactionType.SPLIT || ratioFrom == null || ratioTo == null
                || ratioFrom.signum() <= 0 || ratioTo.signum() <= 0) return null;
        return ratioTo.divide(ratioFrom, 10, java.math.RoundingMode.HALF_UP);
    }

    /**
     * BUY and SELL move money. BONUS adds shares at nil cost, acquired on the allotment date —
     * how the Act treats them. SPLIT changes the share count of every lot held on the
     * record date, keeping each lot's purchase date and total cost; its quantity and price
     * are zero and the ratio is carried in {@link #ratioFrom}/{@link #ratioTo}.
     */
    public enum TransactionType {
        BUY, SELL, BONUS, SPLIT
    }
}
