package com.marketai.ai.llm.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, Long> {

    /** task, provider, model, calls, successes, fallbacks, average latency of successes. */
    @Query("SELECT l.task, l.provider, l.model, COUNT(l), "
         + "SUM(CASE WHEN l.success = true THEN 1 ELSE 0 END), "
         + "SUM(CASE WHEN l.fallback = true THEN 1 ELSE 0 END), "
         + "AVG(CASE WHEN l.success = true THEN l.durationMs ELSE NULL END), "
         + "SUM(CASE WHEN l.success = true THEN l.durationMs ELSE 0 END) "
         + "FROM LlmCallLog l WHERE l.createdAt >= :since GROUP BY l.task, l.provider, l.model")
    List<Object[]> summarise(@Param("since") LocalDateTime since);

    Optional<LlmCallLog> findFirstBySuccessTrueOrderByCreatedAtDesc();

    Optional<LlmCallLog> findFirstBySuccessFalseOrderByCreatedAtDesc();

    @Modifying
    @Query("DELETE FROM LlmCallLog l WHERE l.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
