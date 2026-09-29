package com.marketai.reconciliation.repository;

import com.marketai.reconciliation.entity.DataBackup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DataBackupRepository extends JpaRepository<DataBackup, Long> {
    Optional<DataBackup> findByIdAndUserId(Long id, Long userId);
    List<DataBackup> findByUserIdOrderByCreatedAtDesc(Long userId);
}
