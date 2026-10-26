package com.marketai.reconciliation.repository;

import com.marketai.reconciliation.entity.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {
}
