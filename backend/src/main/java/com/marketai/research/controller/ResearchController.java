package com.marketai.research.controller;

import com.marketai.auth.entity.User;
import com.marketai.research.model.ResearchResult;
import com.marketai.research.service.ResearchOrchestrator;
import com.marketai.research.service.ResearchOrchestrator.Mode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Research for a stock, an index or a fund. {@code mode=cached} returns stored research only
 * (no model call); the default runs research when nothing is stored for the current data;
 * {@code refresh=true} always runs it.
 */
@RestController
@RequestMapping("/api/research")
@RequiredArgsConstructor
public class ResearchController {

    private static final Map<String, String> INDEX_NAMES = Map.of(
            "NIFTY50", "Nifty 50", "^NSEI", "Nifty 50", "BANKNIFTY", "Bank Nifty", "^NSEBANK", "Bank Nifty",
            "SENSEX", "Sensex", "^BSESN", "Sensex", "NIFTYMIDCAP", "Nifty Midcap 50", "^NSEMDCP50", "Nifty Midcap 50");

    private final ResearchOrchestrator research;

    @GetMapping("/stock/{symbol}")
    public ResponseEntity<ResearchResult> stock(@PathVariable String symbol,
                                                @RequestParam(required = false) String name,
                                                @RequestParam(defaultValue = "false") boolean refresh,
                                                @RequestParam(required = false) String mode,
                                                @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(research.stock(symbol, name, user != null ? user.getId() : null, mode(refresh, mode)));
    }

    @GetMapping("/market/{symbol}")
    public ResponseEntity<ResearchResult> market(@PathVariable String symbol,
                                                 @RequestParam(defaultValue = "false") boolean refresh,
                                                 @RequestParam(required = false) String mode) {
        String name = INDEX_NAMES.getOrDefault(symbol.toUpperCase(), symbol);
        return ResponseEntity.ok(research.index(symbol, name, mode(refresh, mode)));
    }

    @GetMapping("/fund/{symbol}")
    public ResponseEntity<ResearchResult> fund(@PathVariable String symbol,
                                               @RequestParam(defaultValue = "false") boolean refresh,
                                               @RequestParam(required = false) String mode,
                                               @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(research.fund(symbol, user != null ? user.getId() : null, mode(refresh, mode)));
    }

    static Mode mode(boolean refresh, String mode) {
        if (refresh) return Mode.REFRESH;
        return "cached".equalsIgnoreCase(mode) ? Mode.CACHED : Mode.AUTO;
    }
}
