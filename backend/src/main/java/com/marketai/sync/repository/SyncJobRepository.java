package com.marketai.sync.repository;

import com.marketai.sync.entity.SyncJob;
import com.marketai.sync.entity.SyncJobStatus;
import com.marketai.sync.entity.SyncJobType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    List<SyncJob> findByStatusOrderByCreatedAtAsc(SyncJobStatus status, PageRequest page);

    Optional<SyncJob> findByIdAndUser_Id(Long id, Long userId);

    List<SyncJob> findByUser_IdOrderByCreatedAtDesc(Long userId, PageRequest page);

    boolean existsByUser_IdAndTypeAndStatusIn(Long userId, SyncJobType type, List<SyncJobStatus> statuses);

    Optional<SyncJob> findFirstByUser_IdOrderByCreatedAtDesc(Long userId);

    /**
     * Atomically take ownership of a queued job.
     *
     * The status check lives in the WHERE clause on purpose: two workers can read the same
     * QUEUED row, but only one UPDATE will match, so exactly one gets a return value of 1.
     * A read-then-write in Java would let both proceed and import every transaction twice.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SyncJob j SET j.status = com.marketai.sync.entity.SyncJobStatus.RUNNING, "
         + "j.claimedBy = :worker, j.claimedAt = :now, j.startedAt = COALESCE(j.startedAt, :now), "
         + "j.attempts = j.attempts + 1 "
         + "WHERE j.id = :id AND j.status = com.marketai.sync.entity.SyncJobStatus.QUEUED")
    int claim(@Param("id") Long id, @Param("worker") String worker, @Param("now") LocalDateTime now);

    /**
     * Jobs left RUNNING by a process that died. Requeued on startup and periodically — without
     * this they would sit RUNNING forever and block the per-user duplicate guard.
     */
    @Query("SELECT j FROM SyncJob j WHERE j.status = com.marketai.sync.entity.SyncJobStatus.RUNNING "
         + "AND j.claimedAt < :staleBefore")
    List<SyncJob> findStaleRunning(@Param("staleBefore") LocalDateTime staleBefore);
}
