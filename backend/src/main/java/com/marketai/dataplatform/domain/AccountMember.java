package com.marketai.dataplatform.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Co-owners of a joint account (the primary owner is on the account itself). */
@Entity
@Table(name = "account_members",
    uniqueConstraints = @UniqueConstraint(name = "uk_am_account_user", columnNames = {"account_id", "user_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AccountMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "account_id", nullable = false) private Long accountId;
    @Column(name = "user_id", nullable = false) private Long userId;
    /** CO_OWNER can see and reconcile; VIEWER can only see. */
    @Column(nullable = false, length = 20) private String role;
    @Column(name = "share_percent", precision = 5, scale = 2) private BigDecimal sharePercent;
}
