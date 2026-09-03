package com.marketai.market.repository;

import com.marketai.market.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findBySymbol(String symbol);
    boolean existsBySymbol(String symbol);

    @Query("SELECT s FROM Stock s WHERE UPPER(s.symbol) LIKE UPPER(CONCAT('%', :q, '%')) " +
           "OR UPPER(s.name) LIKE UPPER(CONCAT('%', :q, '%')) ORDER BY s.symbol")
    List<Stock> searchBySymbolOrName(String q);

    List<Stock> findBySectorAndActiveTrue(String sector);

    @Query("SELECT DISTINCT s.sector FROM Stock s WHERE s.sector IS NOT NULL ORDER BY s.sector")
    List<String> findAllSectors();
}
