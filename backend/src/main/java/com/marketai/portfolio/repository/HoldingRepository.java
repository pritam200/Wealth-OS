package com.marketai.portfolio.repository;

import com.marketai.portfolio.entity.Holding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {
    List<Holding> findByPortfolioId(Long portfolioId);
    Optional<Holding> findByPortfolioIdAndSymbol(Long portfolioId, String symbol);

    /**
     * Loads a holding only if it really sits in the given portfolio. Callers that have already
     * ownership-checked the portfolio must use this instead of {@code findById}: a raw id lookup
     * lets a request pair its own portfolio id with someone else's holding id, and the write then
     * lands on that other user's position.
     */
    Optional<Holding> findByIdAndPortfolioId(Long id, Long portfolioId);

    /** Every AMFI scheme code linked by any user — the work list for the nightly NAV refresh. */
    @Query("SELECT DISTINCT h.amfiSchemeCode FROM Holding h WHERE h.amfiSchemeCode IS NOT NULL")
    List<String> findDistinctAmfiSchemeCodes();

    /** MF holdings not yet linked to an AMFI scheme code. */
    @Query("SELECT h FROM Holding h WHERE h.amfiSchemeCode IS NULL AND h.symbol LIKE '%.MF'")
    List<Holding> findUnlinkedMfHoldings();

    /** Every holding linked to a given AMFI scheme code, across all users — for NAV write-back. */
    List<Holding> findByAmfiSchemeCode(String amfiSchemeCode);

    /** A user's holdings for a given MF folio — used to match a CAS statement's closing balance
     *  back to the holding it's asserting a unit count for. */
    @Query("SELECT h FROM Holding h WHERE h.portfolio.user.id = :userId AND h.folio = :folio")
    List<Holding> findByUserIdAndFolio(@Param("userId") Long userId, @Param("folio") String folio);

    /** A user's holdings linked to a given AMFI scheme code — the fallback match when the CAS
     *  statement's folio isn't itself recorded on the holding. */
    @Query("SELECT h FROM Holding h WHERE h.portfolio.user.id = :userId AND h.amfiSchemeCode = :schemeCode")
    List<Holding> findByUserIdAndAmfiSchemeCode(@Param("userId") Long userId, @Param("schemeCode") String schemeCode);
}
