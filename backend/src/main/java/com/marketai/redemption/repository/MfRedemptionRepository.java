package com.marketai.redemption.repository;

import com.marketai.redemption.entity.MfRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MfRedemptionRepository extends JpaRepository<MfRedemption, Long> {
    List<MfRedemption> findByUserIdOrderByRedemptionDateDesc(Long userId);
}
