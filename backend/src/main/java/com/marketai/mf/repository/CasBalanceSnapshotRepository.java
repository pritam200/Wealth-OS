package com.marketai.mf.repository;

import com.marketai.mf.entity.CasBalanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CasBalanceSnapshotRepository extends JpaRepository<CasBalanceSnapshot, Long> {

    /** Every snapshot for a user, most recent first — used to find each folio's latest statement. */
    List<CasBalanceSnapshot> findByUserIdOrderByAsOfDateDesc(Long userId);

    /** Most recent statement for a specific folio, regardless of whether the scheme resolved. */
    Optional<CasBalanceSnapshot> findTopByUserIdAndFolioOrderByAsOfDateDesc(Long userId, String folio);

    /** Most recent statement for a folio+scheme pair, when the scheme code did resolve. */
    Optional<CasBalanceSnapshot> findTopByUserIdAndFolioAndSchemeCodeOrderByAsOfDateDesc(
            Long userId, String folio, String schemeCode);
}
