package com.marketai.planner.repository;

import com.marketai.planner.entity.MonthlyReflection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MonthlyReflectionRepository extends JpaRepository<MonthlyReflection, Long> {
    Optional<MonthlyReflection> findByUserIdAndYearMonth(Long userId, String yearMonth);
}
