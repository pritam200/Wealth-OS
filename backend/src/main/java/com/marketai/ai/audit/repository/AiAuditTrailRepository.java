package com.marketai.ai.audit.repository;

import com.marketai.ai.audit.entity.AiAuditTrail;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiAuditTrailRepository extends JpaRepository<AiAuditTrail, Long> {
    List<AiAuditTrail> findByUserIdOrderByCreatedAtDesc(Long userId, PageRequest page);
    List<AiAuditTrail> findByUserIdAndTaskOrderByCreatedAtDesc(Long userId, String task, PageRequest page);
    long countByUserIdAndStatus(Long userId, String status);
}
