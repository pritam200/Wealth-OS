package com.marketai.provenance;

import com.marketai.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** "View source" for a ledger record: GET /api/provenance/{expense|income|rent|transaction|fd|rd}/{id}. */
@RestController
@RequestMapping("/api/provenance")
@RequiredArgsConstructor
public class ProvenanceController {

    private final ProvenanceService provenanceService;

    @GetMapping("/{kind}/{id}")
    public ResponseEntity<ProvenanceService.SourceView> source(@AuthenticationPrincipal User user,
                                                               @PathVariable String kind, @PathVariable Long id) {
        return ResponseEntity.ok(provenanceService.sourceOf(user.getId(), kind, id));
    }
}
