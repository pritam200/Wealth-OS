package com.marketai.sync.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-user sync lock, against a real (in-memory) database. */
class SyncLockServiceTest {

    private JdbcTemplate jdbc;
    private SyncLockService locks;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
            "jdbc:h2:mem:synclock" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sync_locks (user_id BIGINT PRIMARY KEY, holder VARCHAR(64) NOT NULL, "
            + "acquired_at TIMESTAMP NOT NULL, heartbeat_at TIMESTAMP NOT NULL)");
        locks = new SyncLockService(ds);
    }

    @Test
    @DisplayName("a second sync for the same user is refused while the first holds the lock")
    void secondSyncIsRefused() {
        var first = locks.tryAcquire(1L);
        assertThat(first).isPresent();
        assertThat(locks.tryAcquire(1L)).isEmpty();
        assertThat(locks.tryAcquire(2L)).isPresent();   // other users are independent
        assertThat(locks.isHeld(1L)).isTrue();

        first.get().close();

        assertThat(locks.isHeld(1L)).isFalse();
        assertThat(locks.tryAcquire(1L)).isPresent();
    }

    @Test
    @DisplayName("a lock left by a process that died is taken over once its heartbeat stops")
    void staleLockIsTakenOver() {
        Timestamp old = Timestamp.valueOf(LocalDateTime.now().minusMinutes(10));
        jdbc.update("INSERT INTO sync_locks VALUES (1, 'dead-instance', ?, ?)", old, old);

        assertThat(locks.isHeld(1L)).isFalse();
        assertThat(locks.tryAcquire(1L)).isPresent();
    }

    @Test
    @DisplayName("a long-running sync that keeps its heartbeat is never taken over")
    void liveLockIsNotTakenOver() {
        var held = locks.tryAcquire(1L).orElseThrow();
        Timestamp old = Timestamp.valueOf(LocalDateTime.now().minusMinutes(10));
        jdbc.update("UPDATE sync_locks SET acquired_at = ?, heartbeat_at = ?", old, old);

        locks.heartbeat();

        assertThat(locks.tryAcquire(1L)).isEmpty();
        held.close();
    }

    @Test
    @DisplayName("releasing a lock that was taken over does not release the new holder's")
    void releaseOnlyRemovesOwnLock() {
        var mine = locks.tryAcquire(1L).orElseThrow();
        Timestamp old = Timestamp.valueOf(LocalDateTime.now().minusMinutes(10));
        jdbc.update("UPDATE sync_locks SET heartbeat_at = ?", old);
        var theirs = locks.tryAcquire(1L);
        assertThat(theirs).isPresent();

        mine.close();

        assertThat(locks.isHeld(1L)).isTrue();
    }
}
