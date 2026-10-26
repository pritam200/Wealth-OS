package com.marketai.gmail.repository;

import com.marketai.gmail.entity.DepositoryMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepositoryMovementRepository extends JpaRepository<DepositoryMovement, Long> {

    boolean existsByUserIdAndGmailMessageIdAndRowNo(Long userId, String gmailMessageId, Integer rowNo);

    boolean existsByUserIdAndGmailMessageId(Long userId, String gmailMessageId);

    List<DepositoryMovement> findByUserIdOrderByMovedAtAsc(Long userId);
}
