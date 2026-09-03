package com.marketai.networth.repository;

import com.marketai.networth.entity.NetWorthSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NetWorthSnapshotRepository extends JpaRepository<NetWorthSnapshot, Long> {
    List<NetWorthSnapshot> findByUserIdOrderBySnapshotDateAsc(Long userId);
    Optional<NetWorthSnapshot> findByUserIdAndSnapshotDate(Long userId, LocalDate snapshotDate);
}
