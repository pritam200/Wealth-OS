package com.marketai.tracking.repository;

import com.marketai.tracking.entity.InsurancePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface InsurancePolicyRepository extends JpaRepository<InsurancePolicy, Long> {
    List<InsurancePolicy> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByIdAndUserId(Long id, Long userId);
    Optional<InsurancePolicy> findByIdAndUserId(Long id, Long userId);
}
