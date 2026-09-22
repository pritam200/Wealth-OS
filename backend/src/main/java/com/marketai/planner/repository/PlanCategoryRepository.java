package com.marketai.planner.repository;

import com.marketai.planner.entity.PlanCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanCategoryRepository extends JpaRepository<PlanCategory, Long> {
    List<PlanCategory> findByUserIdAndActiveTrueOrderBySortOrderAsc(Long userId);
    List<PlanCategory> findByUserIdOrderBySortOrderAsc(Long userId);
    Optional<PlanCategory> findByUserIdAndKey(Long userId, String key);
    boolean existsByUserId(Long userId);
    Optional<PlanCategory> findByIdAndUserId(Long id, Long userId);
}
