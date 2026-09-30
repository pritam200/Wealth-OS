package com.marketai.forecast.controller;

import com.marketai.forecast.dto.ForecastResponse;
import com.marketai.forecast.service.ForecastService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/forecast")
@RequiredArgsConstructor
public class ForecastController {

    private final ForecastService forecastService;

    // Friendly index names the UI can offer in a switcher
    private static final Map<String, String> INDEX_NAMES = new LinkedHashMap<>();
    static {
        INDEX_NAMES.put("NIFTY50", "Nifty 50");
        INDEX_NAMES.put("BANKNIFTY", "Bank Nifty");
        INDEX_NAMES.put("SENSEX", "Sensex");
        INDEX_NAMES.put("NIFTYMIDCAP", "Nifty Midcap 50");
    }

    @GetMapping("/indices")
    public ResponseEntity<Map<String, String>> indices() {
        return ResponseEntity.ok(INDEX_NAMES);
    }

    @GetMapping
    public ResponseEntity<ForecastResponse> forecast(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "20D") String horizon,
            @RequestParam(required = false) String name) {
        String display = name != null ? name : INDEX_NAMES.getOrDefault(symbol.toUpperCase(), symbol);
        return ResponseEntity.ok(forecastService.forecast(symbol, horizon, display));
    }

    /** Walk-forward backtest of the range model — the evidence behind every forecast's calibration figures. */
    @GetMapping("/backtest")
    public ResponseEntity<Map<String, Object>> backtest(
            @RequestParam String symbols,
            @RequestParam(defaultValue = "1D,5D,20D,60D") String horizons) {
        java.util.List<String> syms = java.util.Arrays.stream(symbols.split(",")).map(String::trim).filter(x -> !x.isEmpty()).limit(40).toList();
        java.util.List<String> hs = java.util.Arrays.stream(horizons.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        return ResponseEntity.ok(forecastService.backtest(syms, hs));
    }
}
