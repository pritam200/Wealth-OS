package com.marketai.ai.repository;

import com.marketai.ai.entity.AiHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiHistoryRepository extends JpaRepository<AiHistory, Long> {
    Page<AiHistory> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
