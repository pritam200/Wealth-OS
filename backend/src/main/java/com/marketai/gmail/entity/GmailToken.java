package com.marketai.gmail.entity;

import com.marketai.auth.entity.User;
import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "gmail_tokens")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class GmailToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true, nullable = false)
    private User user;

    @Column(length = 2000)
    private String accessToken;

    @Column(length = 2000)
    private String refreshToken;

    private LocalDateTime expiresAt;
    private String connectedEmail;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastSyncAt;
    private Integer importedCount;

    /**
     * Incremental-sync watermark: the historyId up to which this mailbox has been fully
     * processed.
     *
     * Two rules govern it, both of which protect against silently losing transactions:
     *  1. It is only ever written AFTER every page of history.list has been drained and
     *     processed — advancing early would skip records we never looked at, permanently.
     *  2. It only ever moves forward. Gmail notifications are at-least-once and can repeat or
     *     arrive late, so a lower historyId must be ignored rather than rewinding the mark.
     *
     * Stored as a String because Gmail's historyId is an unsigned 64-bit value.
     */
    @Column(name = "last_history_id", length = 40)
    private String lastHistoryId;

    @Column(name = "history_id_updated_at")
    private LocalDateTime historyIdUpdatedAt;

    /**
     * When the current users.watch registration lapses. Gmail expires it after 7 days and
     * stops delivering notifications with no error and no callback, so this is renewed on a
     * timer and its absence is treated as "push is not active", not as "push is fine".
     */
    @Column(name = "watch_expiration")
    private LocalDateTime watchExpiration;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); importedCount = 0; }
    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
