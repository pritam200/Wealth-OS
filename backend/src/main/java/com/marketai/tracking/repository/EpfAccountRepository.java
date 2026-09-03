package com.marketai.tracking.repository;

import com.marketai.tracking.entity.EpfAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EpfAccountRepository extends JpaRepository<EpfAccount, Long> {
    List<EpfAccount> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByIdAndUserId(Long id, Long userId);
    java.util.Optional<EpfAccount> findByIdAndUserId(Long id, Long userId);
}
