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

public interface RawFinancialDataRepository extends JpaRepository<RawFinancialData, Long> {
    Optional<RawFinancialData> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    List<RawFinancialData> findByUserIdAndProcessingStatus(Long userId, ProcessingStatus status);
    List<RawFinancialData> findByConnectionIdAndProcessingStatus(Long connectionId, ProcessingStatus status);
    long countByUserIdAndProcessingStatus(Long userId, ProcessingStatus status);
}
