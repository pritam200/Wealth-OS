package com.marketai.reconciliation.repository;

import com.marketai.reconciliation.entity.ReconciliationIssueRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReconciliationIssueRecordRepository extends JpaRepository<ReconciliationIssueRecord, Long> {

    List<ReconciliationIssueRecord> findByUserId(Long userId);

    List<ReconciliationIssueRecord> findByUserIdAndStatusInOrderByLastSeenAtDesc(Long userId, List<String> statuses);

    Optional<ReconciliationIssueRecord> findByIdAndUserId(Long id, Long userId);
}
