package com.marketai.research.repository;

import com.marketai.research.entity.ResearchRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ResearchRecordRepository extends JpaRepository<ResearchRecord, Long> {

    Optional<ResearchRecord> findFirstBySubjectTypeAndSymbolAndUserScopeAndMarketDateAndSnapshotHashAndPromptVersionAndProviderAndModelOrderByCreatedAtDesc(
            String subjectType, String symbol, Long userScope, LocalDate marketDate, String snapshotHash,
            String promptVersion, String provider, String model);

    Optional<ResearchRecord> findFirstBySubjectTypeAndSymbolAndMarketDateOrderByCreatedAtDesc(String subjectType, String symbol, LocalDate marketDate);

    Optional<ResearchRecord> findFirstBySubjectTypeAndSymbolAndUserScopeOrderByCreatedAtDesc(String subjectType, String symbol, Long userScope);
}
