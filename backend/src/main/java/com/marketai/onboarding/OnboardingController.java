package com.marketai.onboarding;

import com.marketai.auth.entity.User;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;

/** The API behind the Setup Checklist screen. */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService service;

    @Data public static class AddBody { private String kind; private String name; }
    @Data public static class ThroughBody { private LocalDate date; }

    @GetMapping("/checklist")
    public OnboardingService.Checklist checklist(@AuthenticationPrincipal User user) {
        return service.checklist(user.getId());
    }

    @PostMapping("/sources")
    public OnboardingService.SourceView add(@AuthenticationPrincipal User user, @RequestBody AddBody body) {
        return service.add(user.getId(), body.getKind(), body.getName());
    }

    @DeleteMapping("/sources/{id}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal User user, @PathVariable Long id) {
        service.remove(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/sources/{id}/synced-through")
    public OnboardingService.SourceView syncedThrough(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                      @RequestBody ThroughBody body) {
        return service.setSyncedThrough(user.getId(), id, body.getDate());
    }

    @PostMapping(value = "/sources/{id}/import", consumes = "multipart/form-data")
    public OnboardingService.ImportResult importFile(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                     @RequestParam("file") MultipartFile file,
                                                     @RequestParam(defaultValue = "false") boolean completeStatement) throws IOException {
        return service.importFile(user.getId(), id, file, completeStatement);
    }

    /** One box for any CAS PDF: the kind is detected and a locked file is opened with derived passwords. */
    @PostMapping(value = "/import-cas-auto", consumes = "multipart/form-data")
    public OnboardingService.CasResult importCasAuto(@AuthenticationPrincipal User user,
                                                     @RequestParam("file") MultipartFile file,
                                                     @RequestParam(required = false) String password) throws IOException {
        return service.importCasAuto(user.getId(), file, password);
    }

    /** The CAS password is used in memory to open the PDF and is never stored or logged. */
    @PostMapping(value = "/sources/{id}/import-cas", consumes = "multipart/form-data")
    public OnboardingService.CasResult importCas(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestParam(required = false) String password) throws IOException {
        return service.importCas(user.getId(), id, file, password);
    }
}
