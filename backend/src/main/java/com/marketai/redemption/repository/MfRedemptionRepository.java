package com.marketai.redemption.repository;

import com.marketai.redemption.entity.MfRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface MfRedemptionRepository extends JpaRepository<MfRedemption, Long> {

    List<MfRedemption> findByUserIdAndStatus(Long userId, String status);
    List<MfRedemption> findByUserIdOrderByRedemptionDateDesc(Long userId);

    /**
     * Long-term gains already realised in a financial year — the running figure the ₹1,25,000
     * exemption is consumed from. Null when there are none.
     */
    @Query("""
        SELECT SUM(r.capitalGain) FROM MfRedemption r
        WHERE r.userId = :userId AND r.gainType = 'LTCG' AND r.capitalGain > 0
          AND r.redemptionDate >= :fyStart AND r.redemptionDate <= :fyEnd
        """)
    BigDecimal sumLongTermGainsInFy(@Param("userId") Long userId,
                                    @Param("fyStart") LocalDate fyStart,
                                    @Param("fyEnd") LocalDate fyEnd);
}
