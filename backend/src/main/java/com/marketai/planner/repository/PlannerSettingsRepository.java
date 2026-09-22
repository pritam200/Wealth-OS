package com.marketai.planner.repository;

import com.marketai.planner.entity.PlannerSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlannerSettingsRepository extends JpaRepository<PlannerSettings, Long> {
    Optional<PlannerSettings> findByUserId(Long userId);
}
