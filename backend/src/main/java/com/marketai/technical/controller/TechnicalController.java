package com.marketai.technical.controller;

import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/technical")
@RequiredArgsConstructor
@Tag(name = "Technical Analysis", description = "RSI, MACD, Moving Averages, Bollinger Bands, ATR, Support/Resistance")
public class TechnicalController {

    private final TechnicalIndicatorService technicalService;

    @GetMapping("/{symbol}")
    @Operation(summary = "Full technical analysis for a stock — RSI, MACD, MA, BB, ATR, signal")
    public ResponseEntity<TechnicalAnalysisDto> analyse(@PathVariable String symbol) {
        return ResponseEntity.ok(technicalService.analyse(symbol.toUpperCase()));
    }
}
