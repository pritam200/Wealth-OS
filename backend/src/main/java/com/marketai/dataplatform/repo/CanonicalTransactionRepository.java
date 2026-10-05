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

public interface CanonicalTransactionRepository extends JpaRepository<CanonicalTransaction, Long> {

    /** Candidates for matching an asset-bearing event: same user and asset, dated near it. */
    List<CanonicalTransaction> findByUserIdAndAssetIdAndTransactionDateBetween(Long userId, Long assetId, LocalDate from, LocalDate to);

    /** Candidates for matching a cash event, which names no asset. */
    List<CanonicalTransaction> findByUserIdAndAssetIdIsNullAndTransactionDateBetween(Long userId, LocalDate from, LocalDate to);

    /** Transactions any of whose sources carries this reference. */
    @Query("select t from CanonicalTransaction t where t.userId = :userId and t.id in " +
           "(select s.transactionId from TransactionSource s where upper(s.sourceReference) = upper(:ref))")
    List<CanonicalTransaction> findByAnySourceReference(@Param("userId") Long userId, @Param("ref") String ref);

    Optional<CanonicalTransaction> findByUserIdAndLegacyTransactionId(Long userId, Long legacyTransactionId);

    List<CanonicalTransaction> findByAccountIdAndAssetIdAndStatusIn(Long accountId, Long assetId, Collection<TxnStatus> statuses);

    List<CanonicalTransaction> findByUserIdAndStatusIn(Long userId, Collection<TxnStatus> statuses);

    List<CanonicalTransaction> findByAccountIdInAndStatusInOrderByTransactionDateDescIdDesc(Collection<Long> accountIds, Collection<TxnStatus> statuses);

    /** Entries in an account window, for comparing against an authoritative feed. */
    List<CanonicalTransaction> findByAccountIdAndTransactionDateBetweenAndStatusIn(Long accountId, LocalDate from, LocalDate to, Collection<TxnStatus> statuses);

    List<CanonicalTransaction> findByReconciliationStatusAndIngestedAtBefore(ReconStatus status, LocalDateTime cutoff);

    @Query("select t.reconciliationStatus, count(t) from CanonicalTransaction t " +
           "where t.accountId in :accountIds and t.status in :statuses group by t.reconciliationStatus")
    List<Object[]> countByReconciliation(@Param("accountIds") Collection<Long> accountIds, @Param("statuses") Collection<TxnStatus> statuses);

    @Query("select t.accountId, t.reconciliationStatus, count(t) from CanonicalTransaction t " +
           "where t.accountId in :accountIds and t.status in :statuses group by t.accountId, t.reconciliationStatus")
    List<Object[]> countByAccountAndReconciliation(@Param("accountIds") Collection<Long> accountIds, @Param("statuses") Collection<TxnStatus> statuses);

    @Query("select distinct t.accountId, t.assetId from CanonicalTransaction t where t.userId = :userId and t.assetId is not null")
    List<Object[]> accountAssetPairs(@Param("userId") Long userId);

    long countByUserId(Long userId);
}
