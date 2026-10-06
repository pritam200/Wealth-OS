package com.marketai.admin;

import com.marketai.admin.domain.*;
import com.marketai.admin.service.RequestLogService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminAccessFilterTest extends AdminTestSupport {

    @Test void nonAdminPathsAreUntouched() throws Exception {
        Outcome o = call("GET", "/api/portfolio", OUTSIDER_IP, null);
        assertTrue(o.reachedController());
        assertEquals(0, logRepo.count(), "only admin traffic is logged");
    }

    @Test void nothingConfiguredMeansNobodyGetsIn() throws Exception {
        Outcome o = call("GET", "/api/admin/me", OUTSIDER_IP, "someone@example.com");
        assertEquals(403, o.status());
        assertFalse(o.reachedController());
    }

    @Test void unknownIpIsDenied() throws Exception {
        allowIp("198.51.100.0/24");
        // trusted proxy with a malformed forwarded header: IP cannot be determined
        Outcome o = call("GET", "/api/admin/me", "10.1.1.1", "root@example.com", "X-Forwarded-For", "garbage");
        assertEquals(403, o.status());
    }

    @Test void ipOutsideAllowlistIsDeniedEvenForAnAdminEmail() throws Exception {
        allowIp("198.51.100.0/24");
        assertEquals(403, call("GET", "/api/admin/me", OUTSIDER_IP, "root@example.com").status());
    }

    @Test void unauthenticatedFromAllowedIpGets401() throws Exception {
        allowIp("198.51.100.0/24");
        assertEquals(401, call("GET", "/api/admin/me", OFFICE_IP, null).status());
    }

    @Test void ordinaryUserWithoutEmailEntryIsForbidden() throws Exception {
        allowIp("198.51.100.0/24");
        assertEquals(403, call("GET", "/api/admin/me", OFFICE_IP, "customer@example.com").status());
    }

    @Test void bootstrapAdminOnBootstrapIpNeedsNoDatabaseRows() throws Exception {
        Outcome o = call("GET", "/api/admin/me", BOOT_IP, "root@example.com");
        assertTrue(o.reachedController());
        assertEquals(AdminRole.ADMIN, o.ctx().role());
        assertTrue(o.ctx().bootstrap());
    }

    @Test void emailEntryGrantsItsRole() throws Exception {
        allowIp("198.51.100.0/24");
        allowEmail("dev@example.com", AdminRole.DEVELOPER);
        Outcome o = call("GET", "/api/admin/me", OFFICE_IP, "DEV@example.com");
        assertTrue(o.reachedController());
        assertEquals(AdminRole.DEVELOPER, o.ctx().role());
    }

    @Test void domainEntryNeverGrantsAdmin() {
        assertThrows(RuntimeException.class, () ->
            emails.add(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), EntryKind.DOMAIN, "example.com", AdminRole.ADMIN, null, null));
    }

    @Test void domainEntryGrantsOrdinaryRoleToEveryoneOnTheDomain() throws Exception {
        allowIp("198.51.100.0/24");
        emails.add(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), EntryKind.DOMAIN, "corp.example", AdminRole.READ_ONLY, null, null);
        assertEquals(AdminRole.READ_ONLY, call("GET", "/api/admin/me", OFFICE_IP, "anyone@corp.example").ctx().role());
        assertEquals(403, call("GET", "/api/admin/me", OFFICE_IP, "anyone@evil-corp.example").status());
    }

    @Test void disabledAndExpiredEntriesAreIgnored() throws Exception {
        allowIp("198.51.100.0/24");
        var ctxAdmin = ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true);
        var e = emails.add(ctxAdmin, EntryKind.EMAIL, "off@example.com", AdminRole.DEVELOPER, null, null);
        emails.update(ctxAdmin, e.getId(), null, EntryStatus.DISABLED, null, null);
        assertEquals(403, call("GET", "/api/admin/me", OFFICE_IP, "off@example.com").status());

        var stale = new EmailAllowEntry();
        stale.setKind(EntryKind.EMAIL); stale.setEmailOrDomain("old@example.com"); stale.setRole(AdminRole.DEVELOPER);
        stale.setExpiresAt(java.time.Instant.now().minusSeconds(5));
        emailRepo.save(stale);
        assertEquals(403, call("GET", "/api/admin/me", OFFICE_IP, "old@example.com").status());
    }

    @Test void spoofedForwardedHeaderCannotBorrowAnAllowedAddress() throws Exception {
        allowIp(OFFICE_IP);
        // attacker connects directly and claims to be the office
        Outcome o = call("GET", "/api/admin/me", OUTSIDER_IP, "root@example.com", "X-Forwarded-For", OFFICE_IP);
        assertEquals(403, o.status());
        // through the trusted proxy the real client is the last address the proxy appended
        Outcome viaProxy = call("GET", "/api/admin/me", "10.0.0.5", "root@example.com", "X-Forwarded-For", OFFICE_IP + ", " + OUTSIDER_IP);
        assertEquals(403, viaProxy.status());
        Outcome real = call("GET", "/api/admin/me", "10.0.0.5", "root@example.com", "X-Forwarded-For", OFFICE_IP);
        assertTrue(real.reachedController());
    }

    @Test void denialDoesNotRevealWhichCheckFailed() throws Exception {
        allowIp("198.51.100.0/24");
        String ipDenied = call("GET", "/api/admin/me", OUTSIDER_IP, "root@example.com").response().getContentAsString().replaceAll("\"requestId\":\"[^\"]+\"", "");
        String emailDenied = call("GET", "/api/admin/me", OFFICE_IP, "customer@example.com").response().getContentAsString().replaceAll("\"requestId\":\"[^\"]+\"", "");
        assertEquals(ipDenied, emailDenied);
    }

    @Test void everyRequestIsLoggedWithoutQueryStringOrHeaders() throws Exception {
        allowIp("198.51.100.0/24");
        allowEmail("dev@example.com", AdminRole.DEVELOPER);
        call("GET", "/api/admin/request-logs", OFFICE_IP, "dev@example.com", "Authorization", "Bearer SECRET.JWT.VALUE");
        call("GET", "/api/admin/me", OUTSIDER_IP, null);
        var rows = logRepo.findAll();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "ALLOW".equals(r.getDecision())));
        assertTrue(rows.stream().anyMatch(r -> "DENY".equals(r.getDecision()) && "IP_NOT_ALLOWED".equals(r.getDenyReason())));
        for (var r : rows) assertFalse(r.toString().contains("SECRET.JWT.VALUE"));
    }

    @Test void requestLogSearchFiltersAndEscapesWildcards() throws Exception {
        allowIp("198.51.100.0/24");
        allowEmail("dev@example.com", AdminRole.DEVELOPER);
        call("GET", "/api/admin/me", OFFICE_IP, "dev@example.com");
        call("GET", "/api/admin/me", OUTSIDER_IP, null);
        var svc = new RequestLogService(logRepo);
        assertEquals(1, svc.search(null, null, OUTSIDER_IP, null, null, null, null, 0, 50).getTotalElements());
        assertEquals(1, svc.search(null, null, null, "DEV@example.com", null, null, "allow", 0, 50).getTotalElements());
        assertEquals(0, svc.search(null, null, null, null, "%", null, null, 0, 50).getTotalElements(), "% is literal, not a wildcard");
        assertEquals(2, svc.search(null, null, null, null, "/api/admin", null, null, 0, 50).getTotalElements());
    }

    @Test void rateLimitReturns429() throws Exception {
        // a fresh, tiny limit via a local settings instance
        var limited = new com.marketai.admin.security.AdminAccessFilter(
            new com.marketai.admin.net.ClientIpResolver(""), ips, emails, lockdown, new RequestLogService(logRepo),
            new com.marketai.admin.security.AdminSettings("DEV", "root@example.com", BOOT_IP, 3, ""));
        int last = 0;
        for (int i = 0; i < 5; i++) {
            var req = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/admin/me");
            req.setRequestURI("/api/admin/me"); req.setRemoteAddr(BOOT_IP);
            var res = new org.springframework.mock.web.MockHttpServletResponse();
            limited.doFilter(req, res, new org.springframework.mock.web.MockFilterChain());
            last = res.getStatus();
        }
        assertEquals(429, last);
    }
}
