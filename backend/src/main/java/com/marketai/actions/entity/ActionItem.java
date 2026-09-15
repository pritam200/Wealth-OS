package com.marketai.actions.entity;

import com.marketai.auth.entity.User;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A recommended action the user has interacted with — executed, skipped or snoozed.
 *
 * Today's Actions itself stays a computed view: the engine recomputes recommendations from
 * live data on every load, and rows here only record what the user did about them. That way a
 * stale recommendation is never served from storage, but a dismissed one doesn't keep coming
 * back either.
 *
 * Keyed by (user, actionType, symbol, actionDate) so the same recommendation on the same day
 * resolves to one row no matter how many times the page is reloaded.
 */
@Entity
@Table(name = "action_items",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "action_type", "symbol", "action_date"}),
       indexes = {
           @Index(name = "idx_ai_user_status", columnList = "user_id, status"),
           @Index(name = "idx_ai_user_date", columnList = "user_id, action_date")
       })
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ActionItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)

    @EqualsAndHashCode.Include
    @ToString.Include    private Long id;

    // Real FK, following the Portfolio/Holding pattern rather than the bare `Long userId`
    // used by the older modules.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "action_type", nullable = false, length = 30)
    private String actionType;   // BUY | REDUCE | BOOK_PROFIT | HOLD | WATCH

    @Column(nullable = false, length = 40)
    private String symbol;

    @Column(length = 200)
    private String name;

    @Column(name = "asset_type", length = 10)
    private String assetType;    // STOCK | MF

    // The amount/quantity as recommended at the time the user acted — kept so the queue can
    // still show what was advised even after prices move.
    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(precision = 18, scale = 4)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ActionStatus status = ActionStatus.PENDING;

    @Column(length = 1000)
    private String note;

    // While set and in the future, the action stays hidden from the active queue.
    @Column(name = "snoozed_until")
    private LocalDate snoozedUntil;

    // The advisory day this action belongs to — part of the uniqueness key.
    @Column(name = "action_date", nullable = false)
    private LocalDate actionDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (actionDate == null) actionDate = LocalDate.now();
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
