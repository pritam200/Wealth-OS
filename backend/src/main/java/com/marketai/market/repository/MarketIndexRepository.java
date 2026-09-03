package com.marketai.market.repository;

import com.marketai.market.entity.MarketIndex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketIndexRepository extends JpaRepository<MarketIndex, Long> {
    Optional<MarketIndex> findBySymbol(String symbol);
}
