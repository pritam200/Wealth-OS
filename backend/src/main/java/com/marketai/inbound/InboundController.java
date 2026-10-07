package com.marketai.inbound;

import com.marketai.auth.entity.User;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** The signed-in user's side of the forwarding inbox: their address, saved CAS password and waiting items. */
@RestController
@RequestMapping("/api/onboarding/inbound")
@RequiredArgsConstructor
public class InboundController {

    private final InboundService service;

    @Data public static class PasswordBody { private String password; private boolean remember; }

    @GetMapping
    public InboundService.View view(@AuthenticationPrincipal User user) { return service.view(user.getId()); }

    @PostMapping("/rotate")
    public InboundService.View rotate(@AuthenticationPrincipal User user) { return service.rotate(user.getId()); }

    @PutMapping("/cas-password")
    public ResponseEntity<Void> savePassword(@AuthenticationPrincipal User user, @RequestBody PasswordBody body) {
        service.saveCasPassword(user.getId(), body.getPassword());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/cas-password")
    public ResponseEntity<Void> clearPassword(@AuthenticationPrincipal User user) {
        service.clearCasPassword(user.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/items/{id}/unlock")
    public InboundService.ItemView unlock(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody PasswordBody body) {
        return service.unlock(user.getId(), id, body.getPassword(), body.isRemember());
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> dismiss(@AuthenticationPrincipal User user, @PathVariable Long id) {
        service.dismiss(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
