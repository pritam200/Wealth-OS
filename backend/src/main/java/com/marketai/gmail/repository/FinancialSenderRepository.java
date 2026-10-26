package com.marketai.gmail.repository;

import com.marketai.gmail.entity.FinancialSender;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FinancialSenderRepository extends JpaRepository<FinancialSender, Long> {
    List<FinancialSender> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByUserIdAndDomain(Long userId, String domain);
    boolean existsByIdAndUserId(Long id, Long userId);
}
