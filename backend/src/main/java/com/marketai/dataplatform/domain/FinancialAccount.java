package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A real-world account at an institution: a demat account, an MF folio, a bank account, a loan. */
@Entity
@Table(name = "financial_accounts",
    uniqueConstraints = @UniqueConstraint(name = "uk_fa_owner_key", columnNames = {"owner_user_id", "account_key"}),
    indexes = {@Index(name = "idx_fa_owner", columnList = "owner_user_id"), @Index(name = "idx_fa_family", columnList = "family_id")})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FinancialAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The member who holds (or, for a joint account, primarily holds) the account. */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** Set when the account is visible to a family; null for a purely personal account. */
    @Column(name = "family_id")
    private Long familyId;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    @Builder.Default
    private Ownership ownership = Ownership.INDIVIDUAL;

    @Enumerated(EnumType.STRING) @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(nullable = false, length = 120)
    private String institution;

    /** Stable identity of the account within its owner: normalised institution + external id. */
    @Column(name = "account_key", nullable = false, length = 200)
    private String accountKey;

    /** The institution's own account id, masked to its last four characters. */
    @Column(name = "masked_number", length = 30)
    private String maskedNumber;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(length = 8)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
}
