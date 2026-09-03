package com.marketai.card.repository;

import com.marketai.card.entity.CreditCard;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CreditCardRepository extends JpaRepository<CreditCard, Long> {
    List<CreditCard> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<CreditCard> findByIdAndUserId(Long id, Long userId);
    List<CreditCard> findByUserIdAndLastFour(Long userId, String lastFour);
    List<CreditCard> findByUserIdAndIssuerIgnoreCase(Long userId, String issuer);
}
