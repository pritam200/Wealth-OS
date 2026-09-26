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

    // Ascending order: subscription-cadence detection walks the history forward in time.
    List<Expense> findByUserIdOrderByExpenseDateAsc(Long userId);

    // Stray rows imported before the parser fix that stops Investment-flavoured
    // debits from ever being booked as an Expense — see BankTransactionParser.
    List<Expense> findByUserIdAndCategory(Long userId, ExpenseCategory category);

    List<Expense> findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(
            Long userId, LocalDate from, LocalDate to);

    // Excludes ACCOUNT_TRANSFER: a CRED/CC-bill debit settles a debt already spent against
    // elsewhere, so counting it here would double the real spend it's paying off.
    @Query("SELECT SUM(e.amount) FROM Expense e WHERE e.userId = :userId AND e.expenseDate >= :from "
        + "AND e.expenseDate <= :to AND e.category <> com.marketai.expense.entity.ExpenseCategory.ACCOUNT_TRANSFER")
    BigDecimal sumByUserIdAndDateRange(@Param("userId") Long userId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    @Query("SELECT e.category, SUM(e.amount) FROM Expense e WHERE e.userId = :userId AND e.expenseDate >= :from AND e.expenseDate <= :to GROUP BY e.category")
    List<Object[]> sumByCategory(@Param("userId") Long userId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    boolean existsByIdAndUserId(Long id, Long userId);

    java.util.Optional<Expense> findByIdAndUserId(Long id, Long userId);

    /**
     * Whether this exact email has already produced an expense row — checked directly, not by
     * scanning same-day rows. A parser that couldn't extract a transaction date falls back to
     * "today," so the same email re-imported on three different sync days produced three rows
     * dated three different days: each landed outside the other two's same-day dedup window and
     * none of them ever saw each other.
     */
    boolean existsByUserIdAndSourceEmailId(Long userId, String sourceEmailId);
}
