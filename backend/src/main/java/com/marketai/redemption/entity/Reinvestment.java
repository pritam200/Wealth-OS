package com.marketai.redemption.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One tranche of redeployed cash from an MfRedemption — e.g. "invested ₹40,000 now". */
@Entity
@Table(name = "reinvestments")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Reinvestment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "redemption_id", nullable = false)
    @JsonIgnore
    private MfRedemption redemption;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    private LocalDate date;
    private String targetFund;

    @Column(length = 500)
    private String note;
}
