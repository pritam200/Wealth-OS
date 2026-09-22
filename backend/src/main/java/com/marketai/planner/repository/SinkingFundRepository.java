package com.marketai.planner.repository;

import com.marketai.planner.entity.SinkingFund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SinkingFundRepository extends JpaRepository<SinkingFund, Long> {
    List<SinkingFund> findByUserIdAndActiveTrueOrderBySortOrderAsc(Long userId);
    boolean existsByUserId(Long userId);
    Optional<SinkingFund> findByIdAndUserId(Long id, Long userId);
}
