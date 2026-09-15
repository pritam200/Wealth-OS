package com.marketai.signal.controller;

import com.marketai.signal.dto.SignalPayload;
import com.marketai.signal.service.SignalEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class SignalController {

    private final SignalEngine signalEngine;

    @GetMapping("/{symbol}")
    public ResponseEntity<SignalPayload> get(@PathVariable String symbol) {
        return ResponseEntity.ok(signalEngine.analyse(symbol.toUpperCase()));
    }
}
