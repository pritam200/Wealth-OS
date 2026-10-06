package com.marketai.admin.api;

import com.marketai.admin.data.DataConsoleService;
import com.marketai.admin.domain.AdminChangeRequest;
import com.marketai.admin.mfa.MfaService;
import com.marketai.admin.security.AdminAuthz;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.security.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminDataController {

    private final AdminAuthz authz;
    private final DataConsoleService data;
    private final MfaService mfa;

    public AdminDataController(AdminAuthz authz, DataConsoleService data, MfaService mfa) { this.authz = authz; this.data = data; this.mfa = mfa; }

    public record ChangeBody(Map<String, Object> changes, String reason) {}
    public record DecisionBody(String reason) {}
    public record CodeBody(String code) {}
    public record ResetBody(String email) {}

    @GetMapping("/data/tables")
    public List<Map<String, Object>> tables(HttpServletRequest req) { authz.require(req, Permission.VIEW); return data.tables(); }

    @GetMapping("/data/{table}")
    public DataConsoleService.Page search(HttpServletRequest req, @PathVariable String table, @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        authz.require(req, Permission.VIEW);
        return data.search(table, q, page, size);
    }

    @GetMapping("/data/{table}/{id}")
    public Map<String, Object> get(HttpServletRequest req, @PathVariable String table, @PathVariable String id) {
        authz.require(req, Permission.VIEW);
        return data.get(table, id);
    }

    @PostMapping("/data/{table}/{id}/preview")
    public DataConsoleService.Preview preview(HttpServletRequest req, @PathVariable String table, @PathVariable String id, @RequestBody ChangeBody body) {
        authz.require(req, Permission.EDIT);
        return data.preview(table, id, body.changes());
    }

    @PutMapping("/data/{table}/{id}")
    public Map<String, Object> update(HttpServletRequest req, @PathVariable String table, @PathVariable String id, @RequestBody ChangeBody body) {
        AdminContext ctx = authz.requireStrong(req, Permission.EDIT);
        return data.update(ctx, table, id, body.changes(), body.reason());
    }

    @GetMapping("/data-changes")
    public List<AdminChangeRequest> changeRequests(HttpServletRequest req) { authz.require(req, Permission.VIEW); return data.changeRequests(); }

    @PostMapping("/data-changes/{id}/approve")
    public AdminChangeRequest approve(HttpServletRequest req, @PathVariable Long id, @RequestBody DecisionBody body) {
        return data.decide(authz.requireStrong(req, Permission.MANAGE_ACCESS), id, true, body.reason());
    }

    @PostMapping("/data-changes/{id}/reject")
    public AdminChangeRequest reject(HttpServletRequest req, @PathVariable Long id, @RequestBody DecisionBody body) {
        return data.decide(authz.require(req, Permission.MANAGE_ACCESS), id, false, body.reason());
    }

    // ---- MFA ----

    @GetMapping("/mfa")
    public Map<String, Object> mfaStatus(HttpServletRequest req) {
        AdminContext ctx = authz.require(req, Permission.VIEW);
        return Map.of("required", mfa.required(), "enrolled", mfa.enrolled(ctx.email()), "fresh", mfa.fresh(ctx.email()),
            "freshSeconds", MfaService.FRESH_SECONDS);
    }

    @PostMapping("/mfa/enroll")
    public MfaService.Enrolment enroll(HttpServletRequest req) { return mfa.enroll(authz.require(req, Permission.VIEW)); }

    @PostMapping("/mfa/verify")
    public Map<String, Object> verifyMfa(HttpServletRequest req, @RequestBody CodeBody body) {
        mfa.verify(authz.require(req, Permission.VIEW), body.code());
        return Map.of("verified", true, "freshSeconds", MfaService.FRESH_SECONDS);
    }

    @PostMapping("/mfa/reset")
    public Map<String, Object> resetMfa(HttpServletRequest req, @RequestBody ResetBody body) {
        mfa.reset(authz.requireStrong(req, Permission.MANAGE_ACCESS), body.email());
        return Map.of("reset", true);
    }
}
