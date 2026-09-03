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
}
