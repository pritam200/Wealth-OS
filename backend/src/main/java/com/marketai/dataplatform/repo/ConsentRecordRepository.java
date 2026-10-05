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

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {
    Optional<ConsentRecord> findByProviderIdAndProviderConsentHandle(String providerId, String handle);
    Optional<ConsentRecord> findByIdAndUserId(Long id, Long userId);
    List<ConsentRecord> findByUserIdOrderByIdDesc(Long userId);
}
