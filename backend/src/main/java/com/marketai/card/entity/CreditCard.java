package com.marketai.card.entity;

import javax.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "credit_cards", indexes = {
    @Index(name = "idx_card_user", columnList = "user_id")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CreditCard {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String name;          // e.g. "HDFC Millennia"

    @Column(length = 60)
    private String issuer;        // HDFC, SBI, ICICI, Axis, Amex

    @Column(length = 40)
    private String network;       // Visa, Mastercard, RuPay, Amex

    @Column(name = "last_four", length = 4)
    private String lastFour;

    @Column(name = "annual_fee", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal annualFee = BigDecimal.ZERO;

    @Column(name = "points_balance")
    @Builder.Default
    private Integer pointsBalance = 0;

    @Column(name = "point_value", precision = 8, scale = 4)
    @Builder.Default
    private BigDecimal pointValue = new BigDecimal("0.25"); // ₹ per point

    @Column(name = "billing_day")
    private Integer billingDay;

    @Column(name = "due_day")
    private Integer dueDay;

    @Column(name = "current_due", precision = 12, scale = 2)
    private BigDecimal currentDue;

    @Column(name = "current_due_date")
    private java.time.LocalDate currentDueDate;

    @Column(length = 1000)
    private String benefits;      // human-readable, newline separated

    @Column(name = "best_for", length = 300)
    private String bestFor;       // where to use it

    /** category -> effective reward value in percent (e.g. 5.0 = 5% back) */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "card_reward_rates", joinColumns = @JoinColumn(name = "card_id"))
    @MapKeyColumn(name = "category", length = 40)
    @Column(name = "rate", precision = 6, scale = 2)
    @Builder.Default
    private Map<String, BigDecimal> rewardRates = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
