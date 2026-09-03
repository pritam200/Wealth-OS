package com.marketai.income.repository;

import com.marketai.income.entity.Income;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface IncomeRepository extends JpaRepository<Income, Long> {

    List<Income> findByUserIdOrderByIncomeDateDesc(Long userId);

    List<Income> findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(Long userId, LocalDate from, LocalDate to);

    List<Income> findByUserIdAndSourceAndIncomeDateBetweenOrderByIncomeDateDesc(Long userId, String source, LocalDate from, LocalDate to);

    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM Income i WHERE i.userId = :uid AND i.incomeDate BETWEEN :from AND :to")
    BigDecimal sumByUserIdAndDateRange(@Param("uid") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT i.source, COALESCE(SUM(i.amount), 0) FROM Income i WHERE i.userId = :uid AND i.incomeDate BETWEEN :from AND :to GROUP BY i.source")
    List<Object[]> sumBySource(@Param("uid") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
