package com.marketai.identity.repository;

import com.marketai.identity.entity.FinancialIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FinancialIdentityRepository extends JpaRepository<FinancialIdentity, Long> {
    Optional<FinancialIdentity> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
