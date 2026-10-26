package com.marketai.gmail.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A sender's alert wording learned from one read the model got right (see
 * {@link com.marketai.gmail.parser.LearnedTemplates}). The next alert from that sender, with the
 * same subject shape and wording, is read from it without a model call.
 */
@Entity
@Table(name = "learned_templates",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "pattern_hash"}),
       indexes = @Index(name = "idx_lt_user_sender", columnList = "user_id, sender_domain"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LearnedTemplate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "sender_domain", nullable = false, length = 200)
    private String senderDomain;

    // The subject with its numbers as "#", lower-cased.
    @Column(name = "subject_shape", nullable = false, length = 500)
    private String subjectShape;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String pattern;

    // SHA-256 of sender, subject shape and pattern: one row per distinct shape.
    @Column(name = "pattern_hash", nullable = false, length = 64)
    private String patternHash;

    // Fields every read carries (instrument and transaction type, status) and the date format.
    @Column(nullable = false, length = 1000)
    private String constants;

    // The sentence it was learned from, with account numbers and references masked.
    @Column(length = 500)
    private String sample;

    @Builder.Default
    private int timesUsed = 0;

    private LocalDateTime lastUsedAt;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
