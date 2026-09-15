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
    private final com.marketai.networth.service.NetWorthAttributionService attributionService;

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

    /**
     * Explains the change in net worth over a period.
     *
     * Returns 204 when there is not enough history to compare — a change needs two snapshots,
     * and defaulting the opening value to zero would report a first-ever snapshot as though the
     * user had earned their entire net worth in that window.
     */
    @GetMapping("/attribution")
    public ResponseEntity<com.marketai.networth.attribution.AttributionResult> attribution(
            @AuthenticationPrincipal com.marketai.auth.entity.User user,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso =
                org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso =
                org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {

        java.time.LocalDate end = to != null ? to : java.time.LocalDate.now();
        java.time.LocalDate start = from != null ? from : end.minusDays(30);

        return attributionService.attribute(user.getId(), start, end)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
