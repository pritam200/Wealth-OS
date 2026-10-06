package com.marketai.admin.api;

import com.marketai.admin.config.ConfigConsoleService;
import com.marketai.admin.security.AdminAuthz;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.security.Permission;
import com.marketai.admin.service.AdminOverviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminConfigController {

    private final AdminAuthz authz;
    private final ConfigConsoleService config;
    private final AdminOverviewService overview;

    public AdminConfigController(AdminAuthz authz, ConfigConsoleService config, AdminOverviewService overview) {
        this.authz = authz; this.config = config; this.overview = overview;
    }

    public record RotateBody(String value, String reason) {}
    public record FlagBody(boolean enabled, String reason) {}

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(HttpServletRequest req) { authz.require(req, Permission.VIEW); return overview.dashboard(); }

    @GetMapping("/config")
    public Map<String, Object> config(HttpServletRequest req) {
        authz.require(req, Permission.VIEW);
        return Map.of("items", config.view(), "flags", config.flags(), "rotationAvailable", config.rotationAvailable());
    }

    @PutMapping("/config/secrets/{name}/rotate")
    public Map<String, Object> rotate(HttpServletRequest req, @PathVariable String name, @RequestBody RotateBody body) {
        AdminContext ctx = authz.requireStrong(req, Permission.EDIT);
        config.rotate(ctx, name, body.value(), body.reason());
        return Map.of("rotated", true, "restartRequired", true);
    }

    @PutMapping("/config/flags/{name}")
    public Map<String, Object> flag(HttpServletRequest req, @PathVariable String name, @RequestBody FlagBody body) {
        AdminContext ctx = authz.requireStrong(req, Permission.EDIT);
        config.setFlag(ctx, name, body.enabled(), body.reason());
        return Map.of("name", name, "enabled", body.enabled());
    }

    @GetMapping("/deployment")
    public Map<String, Object> deployment(HttpServletRequest req) { authz.require(req, Permission.VIEW); return overview.deployment(); }

    @GetMapping("/security/sessions")
    public List<Map<String, Object>> sessions(HttpServletRequest req) { authz.require(req, Permission.VIEW); return overview.sessions(); }
}
