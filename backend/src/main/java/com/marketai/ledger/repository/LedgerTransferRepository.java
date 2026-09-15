package com.marketai.ledger.repository;

import com.marketai.ledger.entity.LedgerTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LedgerTransferRepository extends JpaRepository<LedgerTransfer, Long> {
    List<LedgerTransfer> findByUser_IdOrderByTransferDateDesc(Long userId);
    List<LedgerTransfer> findByUser_IdAndTransferDateBetweenOrderByTransferDateDesc(Long userId, LocalDate from, LocalDate to);
    Optional<LedgerTransfer> findByIdAndUser_Id(Long id, Long userId);
    boolean existsByUser_IdAndAmountAndTransferDateAndDestinationType(Long userId, java.math.BigDecimal amount, LocalDate date, String destinationType);
}
