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

public interface TransactionCandidateRepository extends JpaRepository<TransactionCandidate, Long> {
    Optional<TransactionCandidate> findByRawRecordId(Long rawRecordId);
    long countByUserIdAndStatus(Long userId, CandidateStatus status);
    List<TransactionCandidate> findByMatchedTransactionId(Long transactionId);
}
