package com.marketai.networth.controller;

import com.marketai.auth.entity.User;
import com.marketai.networth.entity.NetWorthSnapshot;
import com.marketai.networth.service.NetWorthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/networth")
@RequiredArgsConstructor
public class NetWorthController {

    private final NetWorthService service;

    @GetMapping("/series")
    public ResponseEntity<List<NetWorthSnapshot>> series(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.series(user.getId()));
    }

    @PostMapping("/snapshot")
    public ResponseEntity<NetWorthSnapshot> snapshot(@AuthenticationPrincipal User user,
                                                     @RequestBody Map<String, Object> body) {
        BigDecimal totalAssets = new BigDecimal(String.valueOf(body.getOrDefault("totalAssets", "0")));
        BigDecimal netWorth = new BigDecimal(String.valueOf(body.getOrDefault("netWorth", "0")));
        return ResponseEntity.ok(service.record(user.getId(), totalAssets, netWorth));
    }
}
