package com.marketai.gmail.repository;

import com.marketai.gmail.entity.ExcludedSender;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExcludedSenderRepository extends JpaRepository<ExcludedSender, Long> {
    List<ExcludedSender> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByIdAndUserId(Long id, Long userId);
}
