package com.marketai.sync.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-user sync lock, held in the database ({@code sync_locks}) so it holds across
 * instances — the in-memory lock it replaces only covered one JVM. Plain autocommitted SQL, so
 * taking or releasing the lock never joins (or waits on) a caller's transaction.
 */
@Slf4j
@Service
public class SyncLockService {

    /** A lock whose holder hasn't refreshed it for this long belongs to a process that died. */
    static final Duration STALE_AFTER = Duration.ofMinutes(3);

    private final JdbcTemplate jdbc;

    /** Locks this instance holds: user id → holder token. */
    private final Map<Long, String> held = new ConcurrentHashMap<>();

    public SyncLockService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** A held lock; closing it releases the lock. */
    public final class Handle implements AutoCloseable {
        private final Long userId;
        private final String token;

        private Handle(Long userId, String token) {
            this.userId = userId;
            this.token = token;
        }

        @Override
        public void close() {
            held.remove(userId, token);
            try {
                jdbc.update("DELETE FROM sync_locks WHERE user_id = ? AND holder = ?", userId, token);
            } catch (Exception e) {
                // Left behind, it goes stale and is taken over once the heartbeat stops.
                log.warn("Could not release the sync lock for user {}: {}", userId, e.getMessage());
            }
        }
    }

    /** The lock, or empty when another sync for this user is running (here or elsewhere). */
    public Optional<Handle> tryAcquire(Long userId) {
        String token = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        boolean acquired;
        try {
            acquired = jdbc.update("INSERT INTO sync_locks (user_id, holder, acquired_at, heartbeat_at) VALUES (?, ?, ?, ?)",
                userId, token, now, now) == 1;
        } catch (DataIntegrityViolationException alreadyHeld) {
            // Take it over only if its holder has stopped refreshing it.
            Timestamp staleBefore = Timestamp.valueOf(LocalDateTime.now().minus(STALE_AFTER));
            acquired = jdbc.update("UPDATE sync_locks SET holder = ?, acquired_at = ?, heartbeat_at = ? "
                + "WHERE user_id = ? AND heartbeat_at < ?", token, now, now, userId, staleBefore) == 1;
            if (acquired) log.warn("Took over a stale sync lock for user {}", userId);
        }
        if (!acquired) return Optional.empty();
        held.put(userId, token);
        return Optional.of(new Handle(userId, token));
    }

    /** Whether a sync for this user is running anywhere right now. */
    public boolean isHeld(Long userId) {
        Timestamp staleBefore = Timestamp.valueOf(LocalDateTime.now().minus(STALE_AFTER));
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sync_locks WHERE user_id = ? AND heartbeat_at >= ?",
            Integer.class, userId, staleBefore);
        return n != null && n > 0;
    }

    /** Keeps this instance's locks fresh while their syncs run. */
    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        held.forEach((userId, token) -> {
            try {
                jdbc.update("UPDATE sync_locks SET heartbeat_at = ? WHERE user_id = ? AND holder = ?", now, userId, token);
            } catch (Exception e) {
                log.warn("Sync lock heartbeat failed for user {}: {}", userId, e.getMessage());
            }
        });
    }
}
