package com.marketai.admin.repo;

import com.marketai.admin.domain.AdminRequestLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AdminRequestLogRepository extends JpaRepository<AdminRequestLog, Long>, JpaSpecificationExecutor<AdminRequestLog> {
    long countByOccurredAtAfter(java.time.Instant since);
    long countByOccurredAtAfterAndDecision(java.time.Instant since, String decision);
}
