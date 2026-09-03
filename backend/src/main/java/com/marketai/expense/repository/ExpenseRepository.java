package com.marketai.expense.repository;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findByUserIdOrderByExpenseDateDesc(Long userId);

    // Stray rows imported before the parser fix that stops Investment-flavoured
    // debits from ever being booked as an Expense — see BankTransactionParser.
    List<Expense> findByUserIdAndCategory(Long userId, ExpenseCategory category);

    List<Expense> findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(
            Long userId, LocalDate from, LocalDate to);

    @Query("SELECT SUM(e.amount) FROM Expense e WHERE e.userId = :userId AND e.expenseDate >= :from AND e.expenseDate <= :to")
    BigDecimal sumByUserIdAndDateRange(@Param("userId") Long userId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    @Query("SELECT e.category, SUM(e.amount) FROM Expense e WHERE e.userId = :userId AND e.expenseDate >= :from AND e.expenseDate <= :to GROUP BY e.category")
    List<Object[]> sumByCategory(@Param("userId") Long userId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    boolean existsByIdAndUserId(Long id, Long userId);
}
