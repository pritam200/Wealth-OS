package com.marketai.tracking.repository;

import com.marketai.tracking.entity.OtherAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OtherAssetRepository extends JpaRepository<OtherAsset, Long> {
    List<OtherAsset> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByIdAndUserId(Long id, Long userId);
}
