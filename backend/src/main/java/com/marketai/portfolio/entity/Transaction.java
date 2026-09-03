package com.marketai.portfolio.entity;

import javax.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_txn_holding", columnList = "holding_id"),
        @Index(name = "idx_txn_date", columnList = "transaction_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holding_id", nullable = false)
    private Holding holding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

    @Column(precision = 18, scale = 2)
    private BigDecimal charges;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Transient
    public BigDecimal getTotalAmount() {
        BigDecimal base = price.multiply(quantity);
        return type == TransactionType.BUY
                ? base.add(charges == null ? BigDecimal.ZERO : charges)
                : base.subtract(charges == null ? BigDecimal.ZERO : charges);
    }

    public enum TransactionType {
        BUY, SELL
    }
}
