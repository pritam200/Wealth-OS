package com.marketai.planner.repository;

import com.marketai.planner.entity.SinkingFundEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SinkingFundEntryRepository extends JpaRepository<SinkingFundEntry, Long> {
    List<SinkingFundEntry> findByFundIdOrderByYearMonthAsc(Long fundId);
    Optional<SinkingFundEntry> findByFundIdAndYearMonth(Long fundId, String yearMonth);
    List<SinkingFundEntry> findByFundIdInAndYearMonth(List<Long> fundIds, String yearMonth);
}
