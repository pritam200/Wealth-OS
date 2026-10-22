package com.marketai.card.repository;

import com.marketai.card.entity.DetectedCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DetectedCardRepository extends JpaRepository<DetectedCard, Long> {

    List<DetectedCard> findByUserIdAndStatusOrderByLastSeenAtDesc(Long userId, DetectedCard.Status status);

    List<DetectedCard> findByUserId(Long userId);

    Optional<DetectedCard> findByIdAndUserId(Long id, Long userId);
}
