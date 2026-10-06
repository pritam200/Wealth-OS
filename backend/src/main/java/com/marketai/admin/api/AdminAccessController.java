package com.marketai.admin.api;

import com.marketai.admin.domain.*;
import com.marketai.admin.security.AdminAuthz;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.security.Permission;
import com.marketai.admin.service.AdminAuditService;
import com.marketai.admin.service.EmailAllowlistService;
import com.marketai.admin.service.IpAllowlistService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminAccessController {

    private final AdminAuthz authz;
    private final IpAllowlistService ips;
    private final EmailAllowlistService emails;
    private final AdminAuditService audit;

    public AdminAccessController(AdminAuthz authz, IpAllowlistService ips, EmailAllowlistService emails, AdminAuditService audit) {
        this.authz = authz; this.ips = ips; this.emails = emails; this.audit = audit;
    }

    public record IpRequest(String cidr, String description, Instant expiresAt, EntryStatus status, String reason) {}
    public record EmailRequest(EntryKind kind, String value, AdminRole role, EntryStatus status, Instant expiresAt, String reason) {}

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        AdminContext ctx = authz.require(req, Permission.VIEW);
        audit.record(ctx, "ADMIN_CONSOLE_OPENED", "SESSION", null, "VIEW", null, null, null);
        return Map.of("email", ctx.email(), "role", ctx.role(), "environment", ctx.environment(), "clientIp", ctx.ip(),
            "bootstrap", ctx.bootstrap(), "requestId", ctx.requestId());
    }

    @GetMapping("/ip-allowlist")
    public List<IpAllowEntry> listIps(HttpServletRequest req) { authz.require(req, Permission.VIEW); return ips.list(); }

    @PostMapping("/ip-allowlist")
    public IpAllowEntry addIp(HttpServletRequest req, @RequestBody IpRequest body) {
        AdminContext ctx = authz.require(req, Permission.MANAGE_ACCESS);
        return ips.add(ctx, body.cidr(), body.description(), body.expiresAt(), reason(ctx, body.reason()));
    }

    @PutMapping("/ip-allowlist/{id}")
    public IpAllowEntry updateIp(HttpServletRequest req, @PathVariable Long id, @RequestBody IpRequest body) {
        AdminContext ctx = authz.require(req, Permission.MANAGE_ACCESS);
        return ips.update(ctx, id, body.cidr(), body.description(), body.expiresAt(), body.status(), reason(ctx, body.reason()));
    }

    @DeleteMapping("/ip-allowlist/{id}")
    public Map<String, Object> deleteIp(HttpServletRequest req, @PathVariable Long id, @RequestParam(required = false) String reason) {
        AdminContext ctx = authz.require(req, Permission.MANAGE_ACCESS);
        ips.delete(ctx, id, reason(ctx, reason));
        return Map.of("deleted", true);
    }

    @GetMapping("/email-allowlist")
    public List<EmailAllowEntry> listEmails(HttpServletRequest req) { authz.require(req, Permission.VIEW); return emails.list(); }

    @PostMapping("/email-allowlist")
    public EmailAllowEntry addEmail(HttpServletRequest req, @RequestBody EmailRequest body) {
        AdminContext ctx = authz.require(req, Permission.MANAGE_ACCESS);
        if (body.kind() == null || body.role() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind and role are required.");
        return emails.add(ctx, body.kind(), body.value(), body.role(), body.expiresAt(), reason(ctx, body.reason()));
    }

    @PutMapping("/email-allowlist/{id}")
    public EmailAllowEntry updateEmail(HttpServletRequest req, @PathVariable Long id, @RequestBody EmailRequest body) {
        AdminContext ctx = authz.require(req, Permission.MANAGE_ACCESS);
        return emails.update(ctx, id, body.role(), body.status(), body.expiresAt(), reason(ctx, body.reason()));
    }

    @DeleteMapping("/email-allowlist/{id}")
    public Map<String, Object> deleteEmail(HttpServletRequest req, @PathVariable Long id, @RequestParam(required = false) String reason) {
        AdminContext ctx = authz.require(req, Permission.MANAGE_ACCESS);
        emails.delete(ctx, id, reason(ctx, reason));
        return Map.of("deleted", true);
    }

    /** In PRODUCTION every change needs a written reason. */
    static String reason(AdminContext ctx, String reason) {
        if (ctx.environment() == AdminEnvironment.PRODUCTION && (reason == null || reason.isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required for changes in PRODUCTION.");
        return reason;
    }
}
