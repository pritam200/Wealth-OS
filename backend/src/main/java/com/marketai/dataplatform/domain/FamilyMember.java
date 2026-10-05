package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A user's membership of a family. {@code sharesData} is the member's own consent to let other
 * members see their FAMILY-scoped accounts; without it nothing of theirs is visible.
 */
@Entity
@Table(name = "family_members",
    uniqueConstraints = @UniqueConstraint(name = "uk_fm_family_user", columnNames = {"family_id", "user_id"}),
    indexes = @Index(name = "idx_fm_user", columnList = "user_id"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FamilyMember {
    public static final String OWNER = "OWNER";
    public static final String ADULT = "ADULT";
    public static final String VIEWER = "VIEWER";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "family_id", nullable = false) private Long familyId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 20) private String role;
    /** Whether this member can see the family's shared accounts. */
    @Column(name = "can_view_family", nullable = false) @Builder.Default private boolean canViewFamily = true;
    /** Whether this member exposes their FAMILY-scoped accounts to the others. */
    @Column(name = "shares_data", nullable = false) @Builder.Default private boolean sharesData = false;
}
