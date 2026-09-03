package com.marketai.gmail.entity;

import lombok.*;
import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * A pattern the user has chosen to exclude from Gmail sync entirely — e.g. a shared family
 * inbox that also receives a parent's broker/bank emails. Checked before any parser runs, so
 * an excluded email never gets its transactions imported, never triggers the AI fallback,
 * and never gets queued as a locked PDF either.
 *
 * Matched against the sender address AND the subject/body text — a plain sender-address
 * match isn't enough when the same broker sends both accounts' emails from one address
 * (e.g. mStock's info@mstock.com serves every client); the pattern is usually the parent's
 * client code, folio/account number, or name as it appears in the email body instead.
 */
@Entity
@Table(name = "excluded_senders",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "pattern"}),
       indexes = @Index(name = "idx_es_user", columnList = "user_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExcludedSender {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Case-insensitive substring match against From address + subject + body — e.g.
    // "dad@gmail.com", "MA1234567" (a broker client code), a folio number, or an account
    // holder's name exactly as it appears in the email.
    @Column(nullable = false, length = 320)
    private String pattern;

    @Column(length = 200)
    private String label; // optional human note, e.g. "Dad's mStock account"

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
