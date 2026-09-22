package com.marketai.portfolio.repository;

import com.marketai.portfolio.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByHoldingIdOrderByTransactionDateAsc(Long holdingId);

    /**
     * Every transaction for a set of holdings in one round trip. The portfolio summary used to
     * issue one query per holding while mapping its DTOs, so a 60-holding account cost 60+ round
     * trips on every dashboard load.
     */
    List<Transaction> findByHoldingIdInOrderByTransactionDateAsc(List<Long> holdingIds);
    List<Transaction> findByHoldingIdOrderByTransactionDateDesc(Long holdingId);
    boolean existsByHoldingIdAndTransactionDateAndQuantityAndPrice(Long holdingId, LocalDate transactionDate, BigDecimal quantity, BigDecimal price);

    /** Every sale by this user since a date — the proceeds side of a disposal. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT t FROM Transaction t JOIN t.holding h JOIN h.portfolio p " +
        "WHERE p.user.id = :userId AND t.type = com.marketai.portfolio.entity.Transaction.TransactionType.SELL " +
        "AND t.transactionDate >= :since ORDER BY t.transactionDate DESC")
    List<Transaction> findRecentSales(
        @org.springframework.data.repository.query.Param("userId") Long userId,
        @org.springframework.data.repository.query.Param("since") LocalDate since);

    @org.springframework.data.jpa.repository.Query(
        "SELECT t FROM Transaction t JOIN t.holding h JOIN h.portfolio p " +
        "WHERE p.user.id = :userId AND h.symbol LIKE '%.MF' " +
        "AND t.transactionDate >= :since " +
        "ORDER BY t.transactionDate DESC")
    List<Transaction> findRecentMfTransactions(
        @org.springframework.data.repository.query.Param("userId") Long userId,
        @org.springframework.data.repository.query.Param("since") LocalDate since);
}
