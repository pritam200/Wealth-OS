package com.marketai.admin.api;

import com.marketai.admin.domain.AdminAuditEvent;
import com.marketai.admin.domain.AdminLockdown;
import com.marketai.admin.domain.AdminRequestLog;
import com.marketai.admin.domain.IpAllowEntry;
import com.marketai.admin.security.AdminAuthz;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.security.Permission;
import com.marketai.admin.service.AdminAuditService;
import com.marketai.admin.service.IpAllowlistService;
import com.marketai.admin.service.LockdownService;
import com.marketai.admin.service.RequestLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminSecurityController {

    private final AdminAuthz authz;
    private final RequestLogService logs;
    private final AdminAuditService audit;
    private final LockdownService lockdown;
    private final IpAllowlistService ips;

    public AdminSecurityController(AdminAuthz authz, RequestLogService logs, AdminAuditService audit,
                                   LockdownService lockdown, IpAllowlistService ips) {
        this.authz = authz; this.logs = logs; this.audit = audit; this.lockdown = lockdown; this.ips = ips;
    }

    public record ReasonRequest(String reason) {}

    @GetMapping("/request-logs")
    public Page<AdminRequestLog> requestLogs(HttpServletRequest req,
                                             @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                             @RequestParam(required = false) String ip, @RequestParam(required = false) String email,
                                             @RequestParam(required = false) String path, @RequestParam(required = false) Integer status,
                                             @RequestParam(required = false) String decision,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        authz.require(req, Permission.VIEW);
        return logs.search(from, to, ip, email, path, status, decision, page, size);
    }

    @GetMapping("/audit-logs")
    public Page<AdminAuditEvent> auditLogs(HttpServletRequest req, @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        authz.require(req, Permission.VIEW);
        return audit.page(page, size);
    }

    @GetMapping("/audit-logs/verify")
    public AdminAuditService.Verification verify(HttpServletRequest req) {
        authz.require(req, Permission.VIEW);
        return audit.verify();
    }

    @GetMapping("/security/lockdown")
    public AdminLockdown lockdownStatus(HttpServletRequest req) { authz.require(req, Permission.VIEW); return lockdown.status(); }

    @PostMapping("/security/lockdown")
    public AdminLockdown engage(HttpServletRequest req, @RequestBody ReasonRequest body) {
        AdminContext ctx = authz.require(req, Permission.MANAGE_ACCESS);
        return lockdown.engage(ctx, AdminAccessController.reason(ctx, body.reason()));
    }

    @PostMapping("/security/lockdown/release")
    public AdminLockdown release(HttpServletRequest req, @RequestBody ReasonRequest body) {
        AdminContext ctx = authz.require(req, Permission.MANAGE_ACCESS);
        return lockdown.release(ctx, AdminAccessController.reason(ctx, body.reason()));
    }

    @PostMapping("/security/ip-allowlist/{id}/disable")
    public IpAllowEntry disableIp(HttpServletRequest req, @PathVariable Long id, @RequestBody ReasonRequest body) {
        AdminContext ctx = authz.require(req, Permission.MANAGE_ACCESS);
        return ips.disable(ctx, id, AdminAccessController.reason(ctx, body.reason()));
    }

    @PostMapping("/security/accounts/{email:.+}/disable")
    public Map<String, Object> disableAccount(HttpServletRequest req, @PathVariable String email, @RequestBody ReasonRequest body) {
        AdminContext ctx = authz.require(req, Permission.MANAGE_ACCESS);
        lockdown.disableAccount(ctx, email, AdminAccessController.reason(ctx, body.reason()));
        return Map.of("disabled", true);
    }
}
