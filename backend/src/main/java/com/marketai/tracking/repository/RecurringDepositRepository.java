package com.marketai.tracking.repository;

import com.marketai.tracking.entity.RecurringDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RecurringDepositRepository extends JpaRepository<RecurringDeposit, Long> {
    List<RecurringDeposit> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByIdAndUserId(Long id, Long userId);
    java.util.Optional<RecurringDeposit> findByIdAndUserId(Long id, Long userId);
    boolean existsByUser_IdAndBankAndMonthlyAmountAndStartDate(Long userId, String bank, java.math.BigDecimal monthlyAmount, java.time.LocalDate startDate);
}
