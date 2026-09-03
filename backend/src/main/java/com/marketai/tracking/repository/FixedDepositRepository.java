package com.marketai.tracking.repository;

import com.marketai.tracking.entity.FixedDeposit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FixedDepositRepository extends JpaRepository<FixedDeposit, Long> {
    List<FixedDeposit> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByIdAndUserId(Long id, Long userId);
    java.util.Optional<FixedDeposit> findByIdAndUserId(Long id, Long userId);
    boolean existsByUser_IdAndBankAndPrincipalAndStartDate(Long userId, String bank, java.math.BigDecimal principal, java.time.LocalDate startDate);

    // Candidate predecessors for renewal-matching: same bank, still ACTIVE (a status stays
    // ACTIVE forever unless something — closeFd() or a detected renewal — changes it, so this
    // is every FD from that bank that hasn't already been resolved one way or the other).
    List<FixedDeposit> findByUser_IdAndBankIgnoreCaseAndStatus(Long userId, String bank, String status);
}
