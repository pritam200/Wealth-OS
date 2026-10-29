package com.marketai.market.repository;

import com.marketai.market.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

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

    /**
     * Daily bars in a range stored under any of {@code symbols} — one instrument can have rows
     * under more than one name ("RELIANCE" and the older "RELIANCE.NS"). May hold more than one
     * row per date; readers collapse them with {@code PriceSeriesValidator.latestPerDate}.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM PriceHistory p WHERE p.symbol IN :symbols AND p.date BETWEEN :from AND :to"
            + " AND (p.interval IS NULL OR p.interval = '1d')")
    List<PriceHistory> findDailyBySymbolsAndDateBetween(
        @org.springframework.data.repository.query.Param("symbols") java.util.Collection<String> symbols,
        @org.springframework.data.repository.query.Param("from") LocalDate from,
        @org.springframework.data.repository.query.Param("to") LocalDate to);

    /** Newest-first daily bars under any of {@code symbols} (legacy rows with no interval count as daily). */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM PriceHistory p WHERE p.symbol IN :symbols AND (p.interval IS NULL OR p.interval = '1d') ORDER BY p.date DESC")
    List<PriceHistory> findNewestDaily(
        @org.springframework.data.repository.query.Param("symbols") java.util.Collection<String> symbols,
        org.springframework.data.domain.Pageable page);

    /**
     * Newest daily bar on or before {@code date} under any of {@code symbols} — used for the
     * 31 Jan 2018 grandfathering FMV when the exact calendar day is a market holiday.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM PriceHistory p WHERE p.symbol IN :symbols AND p.date <= :date"
            + " AND (p.interval IS NULL OR p.interval = '1d') ORDER BY p.date DESC")
    List<PriceHistory> findNewestDailyOnOrBefore(
        @org.springframework.data.repository.query.Param("symbols") java.util.Collection<String> symbols,
        @org.springframework.data.repository.query.Param("date") LocalDate date,
        org.springframework.data.domain.Pageable page);

    /** Every symbol with a daily bar on or after {@code since}. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT DISTINCT p.symbol FROM PriceHistory p WHERE p.date >= :since AND (p.interval IS NULL OR p.interval = '1d')")
    List<String> findDailySymbolsSince(@org.springframework.data.repository.query.Param("since") LocalDate since);


    /** Intraday bars for one timeframe, newest first. Empty until the intraday backfill runs. */
    java.util.List<PriceHistory> findBySymbolAndIntervalOrderByBarStartDesc(String symbol, String interval);
}
