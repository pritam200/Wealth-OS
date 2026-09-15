package com.marketai.ledger.repository;

import com.marketai.ledger.entity.CashAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CashAccountRepository extends JpaRepository<CashAccount, Long> {
    List<CashAccount> findByUser_IdAndActiveTrueOrderByNameAsc(Long userId);
    List<CashAccount> findByUser_Id(Long userId);
    Optional<CashAccount> findByIdAndUser_Id(Long id, Long userId);

    @Query("SELECT COALESCE(SUM(c.balance), 0) FROM CashAccount c WHERE c.user.id = :uid AND c.active = true")
    BigDecimal sumBalanceByUser(@Param("uid") Long userId);
}
