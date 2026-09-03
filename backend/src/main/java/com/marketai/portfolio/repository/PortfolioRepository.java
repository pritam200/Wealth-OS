package com.marketai.portfolio.repository;

import com.marketai.portfolio.entity.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {

    // Ordered by id so "the user's portfolio" always resolves to the same row across
    // sessions/imports. Unordered findByUserId let getOrCreatePortfolio() (and the frontend's
    // own portfolio picks) land on a different row from one import to the next, silently
    // fragmenting one user's holdings across multiple portfolio rows.
    List<Portfolio> findByUserIdOrderByIdAsc(Long userId);

    List<Portfolio> findByUserId(Long userId);

    @Query("SELECT p FROM Portfolio p LEFT JOIN FETCH p.holdings WHERE p.id = :id AND p.user.id = :userId")
    Optional<Portfolio> findByIdAndUserId(Long id, Long userId);
}
