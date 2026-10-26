package com.marketai.gmail.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A sender domain the user added to the ones a sync reads, for a bank, broker or fund house
 * not in {@link com.marketai.document.classify.IssuerDomainRegistry}. Mail from anyone else is
 * never fetched or sent to the extractor.
 */
@Entity
@Table(name = "financial_senders",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "domain"}),
       indexes = @Index(name = "idx_fs_user", columnList = "user_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FinancialSender {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Lower-cased domain, e.g. "aubank.in"; its subdomains are included.
    @Column(nullable = false, length = 200)
    private String domain;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
