package com.marketai.networth.controller;

import com.marketai.auth.entity.User;
import com.marketai.networth.entity.NetWorthSnapshot;
import com.marketai.networth.service.NetWorthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/networth")
@RequiredArgsConstructor
public class NetWorthController {

    private final NetWorthService service;

    @GetMapping("/series")
    public ResponseEntity<List<NetWorthSnapshot>> series(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.series(user.getId()));
    }

    /** Records today's snapshot from the server-computed wealth summary — never trusts a
     *  client-supplied total (see {@link NetWorthService#record}). */
    @PostMapping("/snapshot")
    public ResponseEntity<NetWorthSnapshot> snapshot(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.record(user.getId()));
    }
}
