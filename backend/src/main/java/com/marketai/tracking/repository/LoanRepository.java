package com.marketai.tracking.repository;

import com.marketai.tracking.entity.Loan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LoanRepository extends JpaRepository<Loan, Long> {
    List<Loan> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByIdAndUserId(Long id, Long userId);
    java.util.Optional<Loan> findByIdAndUserId(Long id, Long userId);
}
