package com.marketai.mf.repository;

import com.marketai.mf.entity.MfNavHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface MfNavHistoryRepository extends JpaRepository<MfNavHistory, Long> {

    List<MfNavHistory> findBySchemeCodeOrderByDateAsc(String schemeCode);

    List<MfNavHistory> findBySchemeCodeAndDateBetweenOrderByDateAsc(
            String schemeCode, LocalDate from, LocalDate to);

    boolean existsBySchemeCodeAndDate(String schemeCode, LocalDate date);

    /**
     * Dates already stored for a scheme. A scheme's history is thousands of rows, so the
     * insert-only-what's-missing filter loads the date set once rather than issuing one
     * existsBy… query per candidate row.
     */
    @Query("SELECT h.date FROM MfNavHistory h WHERE h.schemeCode = :schemeCode")
    List<LocalDate> findDatesBySchemeCode(@Param("schemeCode") String schemeCode);

    long countBySchemeCode(String schemeCode);
}
