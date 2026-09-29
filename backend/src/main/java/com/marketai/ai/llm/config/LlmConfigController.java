package com.marketai.ai.llm.config;

import com.marketai.ai.llm.ModelInfo;
import com.marketai.auth.entity.User;
import com.marketai.auth.service.AdminAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Settings → LLM Configuration. Everything but {@code /access} is admin-only: the configuration
 * decides where every user's documents are sent. The API key is write-only — no response carries it.
 */
@RestController
@RequestMapping("/api/llm-config")
@RequiredArgsConstructor
public class LlmConfigController {

    private final LlmAdminService admin;
    private final AdminAccess access;

    /** Whether to show the tab at all. */
    @GetMapping("/access")
    public Map<String, Boolean> access(@AuthenticationPrincipal User user) {
        return Map.of("canConfigure", access.isAdmin(user));
    }

    @GetMapping
    public LlmAdminService.ConfigView get(@AuthenticationPrincipal User user) {
        access.require(user);
        return admin.view();
    }

    @PutMapping
    public LlmAdminService.ConfigView save(@AuthenticationPrincipal User user, @RequestBody LlmAdminService.ConfigUpdate update) {
        access.require(user);
        return admin.save(update, user.getId());
    }

    @PostMapping("/test")
    public LlmAdminService.TestResult test(@AuthenticationPrincipal User user, @RequestBody LlmAdminService.TestRequest request) {
        access.require(user);
        return admin.test(request);
    }

    @GetMapping("/models")
    public List<ModelInfo> models(@AuthenticationPrincipal User user, @RequestParam String provider) {
        access.require(user);
        return admin.models(provider);
    }

    @GetMapping("/health")
    public LlmAdminService.Health health(@AuthenticationPrincipal User user) {
        access.require(user);
        return admin.health();
    }
}
