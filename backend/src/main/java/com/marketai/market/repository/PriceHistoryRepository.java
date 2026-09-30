package com.marketai.market.repository;

import com.marketai.market.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    List<PriceHistory> findBySymbolAndDateBetweenOrderByDateAsc(
            String symbol, LocalDate from, LocalDate to);

    List<PriceHistory> findTop200BySymbolOrderByDateDesc(String symbol);

    boolean existsBySymbolAndDate(String symbol, LocalDate date);

    /**
     * The dates already stored for a symbol in a range. One query replaces the per-bar
     * existence check the history backfill used to run — ~250 selects per ticker for a 1y range,
     * every night, for every tracked index.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p.date FROM PriceHistory p WHERE p.symbol = :symbol AND p.date BETWEEN :from AND :to")
    List<LocalDate> findDatesBySymbolAndDateBetween(
        @org.springframework.data.repository.query.Param("symbol") String symbol,
        @org.springframework.data.repository.query.Param("from") LocalDate from,
        @org.springframework.data.repository.query.Param("to") LocalDate to);

    /** Stored daily bars in a range — the rows a history fetch updates in place. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM PriceHistory p WHERE p.symbol = :symbol AND p.date BETWEEN :from AND :to"
            + " AND (p.interval IS NULL OR p.interval = '1d')")
    List<PriceHistory> findDailyBySymbolAndDateBetween(
        @org.springframework.data.repository.query.Param("symbol") String symbol,
        @org.springframework.data.repository.query.Param("from") LocalDate from,
        @org.springframework.data.repository.query.Param("to") LocalDate to);

    /** Newest-first daily bars (legacy rows with no interval count as daily). */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM PriceHistory p WHERE p.symbol = :symbol AND (p.interval IS NULL OR p.interval = '1d') ORDER BY p.date DESC")
    List<PriceHistory> findDailyBySymbolOrderByDateDesc(
        @org.springframework.data.repository.query.Param("symbol") String symbol,
        org.springframework.data.domain.Pageable page);

    /** Every symbol with a daily bar on or after {@code since}. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT DISTINCT p.symbol FROM PriceHistory p WHERE p.date >= :since AND (p.interval IS NULL OR p.interval = '1d')")
    List<String> findDailySymbolsSince(@org.springframework.data.repository.query.Param("since") LocalDate since);

    /** Newest stored daily bar, to tell whether a symbol's history needs topping up. */
    java.util.Optional<PriceHistory> findTopBySymbolOrderByDateDesc(String symbol);

    /** Intraday bars for one timeframe, newest first. Empty until the intraday backfill runs. */
    java.util.List<PriceHistory> findBySymbolAndIntervalOrderByBarStartDesc(String symbol, String interval);
}
