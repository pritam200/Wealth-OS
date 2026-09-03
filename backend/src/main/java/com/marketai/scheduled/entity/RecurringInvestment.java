package com.marketai.scheduled.entity;

import com.marketai.auth.entity.User;
import lombok.*;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single entity for SIP/PPF/NPS recurring contributions (not three near-duplicates),
 * modeled directly on the existing RecurringDeposit shape. SIP entries link to a Holding
 * (linkedSymbol) so status (upcoming/completed/missed) can be derived from real Transaction
 * history instead of a separate installment ledger; PPF/NPS use schedule math only, same
 * as how RecurringDeposit reminders already work.
 */
@Entity
@Table(name = "recurring_investments", indexes = @Index(name = "idx_ri_user", columnList = "user_id"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RecurringInvestment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type; // SIP | PPF | NPS

    @Column(nullable = false, length = 200)
    private String label; // fund name, or "PPF - SBI", etc.

    @Column(name = "linked_symbol")
    private String linkedSymbol; // set for SIP only — the .MF Holding symbol

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "tenure_months")
    private Integer tenureMonths; // null = ongoing/no fixed end

    @Builder.Default
    @Column(length = 12)
    private String status = "ACTIVE"; // ACTIVE | PAUSED | COMPLETED

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Type { SIP, PPF, NPS }
}
