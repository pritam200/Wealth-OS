package com.marketai.card.repository;

import com.marketai.card.entity.CardPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardPaymentRepository extends JpaRepository<CardPayment, Long> {
    List<CardPayment> findByCardIdOrderByPaymentDateDesc(Long cardId);
    List<CardPayment> findByUserIdOrderByPaymentDateDesc(Long userId);
}
