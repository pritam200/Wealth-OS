package com.marketai.dataplatform.repo;

import com.marketai.dataplatform.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HoldingSnapshotRepository extends JpaRepository<HoldingSnapshot, Long> {
    Optional<HoldingSnapshot> findByAccountIdAndAssetIdAndBasis(Long accountId, Long assetId, BasisType basis);
    List<HoldingSnapshot> findByUserId(Long userId);
    List<HoldingSnapshot> findByAccountIdIn(Collection<Long> accountIds);
    List<HoldingSnapshot> findByAssetIdAndAccountIdIn(Long assetId, Collection<Long> accountIds);
}
