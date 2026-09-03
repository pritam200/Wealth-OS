package com.marketai.market.controller;

import com.marketai.market.dto.MarketOverviewDto;
import com.marketai.market.dto.QuoteDto;
import com.marketai.market.entity.PriceHistory;
import com.marketai.market.entity.Stock;
import com.marketai.market.service.MarketDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
@Tag(name = "Market Data", description = "Real-time and historical market data")
@SecurityRequirement(name = "bearerAuth")
public class MarketController {

    private final MarketDataService marketDataService;

    @GetMapping("/overview")
    @Operation(summary = "Get market overview — Nifty, Sensex, BankNifty, sectors")
    public ResponseEntity<MarketOverviewDto> getOverview() {
        return ResponseEntity.ok(marketDataService.getMarketOverview());
    }

    @GetMapping("/quote/{symbol}")
    @Operation(summary = "Get real-time quote for a stock")
    public ResponseEntity<QuoteDto> getQuote(@PathVariable String symbol) {
        return ResponseEntity.ok(marketDataService.getQuote(symbol.toUpperCase()));
    }

    @GetMapping("/search")
    @Operation(summary = "Search stocks by symbol or name")
    public ResponseEntity<List<Stock>> search(@RequestParam String q) {
        return ResponseEntity.ok(marketDataService.searchStocks(q));
    }

    @GetMapping("/history/{symbol}")
    @Operation(summary = "Get OHLCV price history")
    public ResponseEntity<List<PriceHistory>> getHistory(
            @PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(marketDataService.getPriceHistory(symbol.toUpperCase(), from, to));
    }

    @PostMapping("/history/{symbol}/fetch")
    @Operation(summary = "Fetch and store price history from Yahoo Finance")
    public ResponseEntity<Void> fetchHistory(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1y") String range) {
        marketDataService.fetchAndStorePriceHistory(symbol.toUpperCase(), range);
        return ResponseEntity.accepted().build();
    }
}
