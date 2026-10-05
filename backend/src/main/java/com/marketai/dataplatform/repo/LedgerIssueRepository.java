package com.marketai.dataplatform.repo;

import com.marketai.dataplatform.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LedgerIssueRepository extends JpaRepository<LedgerIssue, Long> {
    Optional<LedgerIssue> findByUserIdAndIssueKey(Long userId, String issueKey);
    List<LedgerIssue> findByUserIdOrderByLastSeenAtDesc(Long userId);
    List<LedgerIssue> findByUserIdAndStatusInOrderByLastSeenAtDesc(Long userId, Collection<IssueStatus> statuses);
    List<LedgerIssue> findByUserIdAndTypeAndStatusIn(Long userId, IssueType type, Collection<IssueStatus> statuses);
    List<LedgerIssue> findByAccountIdInAndStatusInOrderByLastSeenAtDesc(Collection<Long> accountIds, Collection<IssueStatus> statuses);
    List<LedgerIssue> findByTransactionIdOrOtherTransactionId(Long transactionId, Long otherTransactionId);
}
