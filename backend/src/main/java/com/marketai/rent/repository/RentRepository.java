package com.marketai.rent.repository;

import com.marketai.rent.entity.Rent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RentRepository extends JpaRepository<Rent, Long> {

    List<Rent> findByUserIdOrderByMonthDesc(Long userId);

    Optional<Rent> findByUserIdAndMonthAndScheduleId(Long userId, LocalDate month, Long scheduleId);

    Optional<Rent> findByUserIdAndMonthAndPaidDateIsNull(Long userId, LocalDate month);

    List<Rent> findByUserIdAndMonthAndAmountAndPaidDateIsNull(Long userId, LocalDate month, BigDecimal amount);

    boolean existsByUserIdAndSourceEmailId(Long userId, String sourceEmailId);

    boolean existsByIdAndUserId(Long id, Long userId);
}
