package com.marketai.gmail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One security moved into or out of a demat account, as the depository's notice states it (see
 * {@link com.marketai.gmail.parser.DepositoryMovementParser}). Never booked; the reconciliation
 * checks the trades against it.
 */
@Entity
@Table(name = "depository_movements",
       uniqueConstraints = @UniqueConstraint(name = "uk_dm_user_msg_row", columnNames = {"user_id", "gmail_message_id", "row_no"}),
       indexes = @Index(name = "idx_dm_user_moved", columnList = "user_id, moved_at"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DepositoryMovement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "gmail_message_id", nullable = false, length = 100)
    private String gmailMessageId;

    @Column(name = "row_no", nullable = false)
    private Integer rowNo;

    // Last four digits of the demat account only.
    @Column(name = "account_suffix", length = 4)
    private String accountSuffix;

    @Column(name = "company_name", length = 300)
    private String companyName;

    @Column(nullable = false, length = 12)
    private String isin;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false)
    private boolean credit;

    @Column(name = "moved_at", nullable = false)
    private LocalDateTime movedAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
