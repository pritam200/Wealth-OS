package com.marketai.ai.review.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A decision the user made once on a review item, remembered for every later item of the same
 * kind: same sender address, same kind of subject, same proposed type and same review reason
 * (see {@link com.marketai.ai.review.service.ReviewRuleService#kindKey}). Without it, a sender
 * that sends the same uncertain email every week asked the same question every week.
 */
@Entity
@Table(name = "review_rules",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "kind_key"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReviewRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "kind_key", nullable = false, length = 64)
    private String kindKey;

    /** ACCEPT or REJECT. Edits are specific to one item and are never remembered. */
    @Column(nullable = false, length = 10)
    private String decision;

    /** Shown in the list of remembered decisions so the user can tell them apart. */
    @Column(length = 320)
    private String sender;

    @Column(length = 500)
    private String subject;

    @Column(name = "review_reason", length = 500)
    private String reviewReason;

    @Column(name = "proposed_type", length = 40)
    private String proposedType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
