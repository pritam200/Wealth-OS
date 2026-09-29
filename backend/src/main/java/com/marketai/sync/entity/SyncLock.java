package com.marketai.sync.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * At most one mailbox sync per user at a time, across every running instance of the app. The
 * row exists while a sync holds it; the holder refreshes {@code heartbeatAt} while it works, so
 * a lock left behind by a process that died can be taken over — and a long but live sync
 * cannot.
 */
@Entity
@Table(name = "sync_locks")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SyncLock {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 64)
    private String holder;

    @Column(name = "acquired_at", nullable = false)
    private LocalDateTime acquiredAt;

    @Column(name = "heartbeat_at", nullable = false)
    private LocalDateTime heartbeatAt;
}
