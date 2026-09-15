package com.marketai.identity.controller;

import com.marketai.auth.entity.User;
import com.marketai.identity.dto.FinancialIdentityRequest;
import com.marketai.identity.dto.FinancialIdentityStatus;
import com.marketai.identity.service.FinancialIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Financial identity (PAN / date of birth) used to derive statement passwords.
 *
 * <p>Note what is absent: there is no endpoint that returns a stored PAN or date of birth, by
 * design. {@link #status} reports only whether each value is present. A "reveal" endpoint would
 * put a government identifier into a response body, a browser cache, and a proxy log, to no
 * benefit — the user already knows their own PAN, and the only consumer that needs the value is
 * the password engine running server-side.
 */
@RestController
@RequestMapping("/api/identity")
@RequiredArgsConstructor
public class FinancialIdentityController {

    private final FinancialIdentityService service;

    @GetMapping
    public ResponseEntity<FinancialIdentityStatus> status(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(FinancialIdentityStatus.of(
            service.hasPan(user.getId()), service.hasDob(user.getId())));
    }

    @PutMapping
    public ResponseEntity<FinancialIdentityStatus> save(@AuthenticationPrincipal User user,
                                                        @RequestBody FinancialIdentityRequest req) {
        service.save(user.getId(), req.pan(), req.dateOfBirth());
        return ResponseEntity.ok(FinancialIdentityStatus.of(
            service.hasPan(user.getId()), service.hasDob(user.getId())));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal User user) {
        service.clear(user.getId());
        return ResponseEntity.noContent().build();
    }
}
