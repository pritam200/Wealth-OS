package com.marketai.rent.repository;

import com.marketai.rent.entity.RentSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RentScheduleRepository extends JpaRepository<RentSchedule, Long> {

    List<RentSchedule> findByUserIdAndActiveTrue(Long userId);

    List<RentSchedule> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<RentSchedule> findByIdAndUserId(Long id, Long userId);
}
