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

public interface FinancialAssetRepository extends JpaRepository<FinancialAsset, Long> {
    Optional<FinancialAsset> findFirstByIsin(String isin);
    Optional<FinancialAsset> findFirstByAssetClassAndAssetKey(AssetClass assetClass, String assetKey);
    Optional<FinancialAsset> findFirstByAssetClassAndNameKey(AssetClass assetClass, String nameKey);
    Optional<FinancialAsset> findFirstByAmfiCode(String amfiCode);
}
