package com.marketai.tracking.repository;

import com.marketai.tracking.entity.RecurringDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RecurringDepositRepository extends JpaRepository<RecurringDeposit, Long> {
    List<RecurringDeposit> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByIdAndUserId(Long id, Long userId);
    java.util.Optional<RecurringDeposit> findByIdAndUserId(Long id, Long userId);
    boolean existsByUser_IdAndBankAndMonthlyAmountAndStartDate(Long userId, String bank, java.math.BigDecimal monthlyAmount, java.time.LocalDate startDate);

    // Candidate predecessors for renewal-matching, mirroring FixedDepositRepository's
    // equivalent query — every RD from this bank that hasn't already been resolved.
    List<RecurringDeposit> findByUser_IdAndBankIgnoreCaseAndStatus(Long userId, String bank, String status);
    List<RecurringDeposit> findByUser_IdAndBankIgnoreCaseAndStatusIn(Long userId, String bank, List<String> statuses);

    List<RecurringDeposit> findByStatus(String status);
}
