package com.marketai.admin;

import com.marketai.admin.domain.*;
import com.marketai.admin.service.AdminAuditService;
import com.marketai.admin.service.Redactor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdminGovernanceTest extends AdminTestSupport {

    @Autowired JdbcTemplate jdbc;

    // ---- self-lockout ------------------------------------------------------------------------

    @Test void cannotRemoveTheOnlyEntryAllowingYourOwnAddress() {
        var me = ctx("dev-admin@example.com", OFFICE_IP, AdminRole.ADMIN, false);
        var entry = ips.add(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), "198.51.100.0/24", "office", null, null);
        assertThrows(ResponseStatusException.class, () -> ips.delete(me, entry.getId(), null));
        assertThrows(ResponseStatusException.class, () -> ips.disable(me, entry.getId(), null));
        assertThrows(ResponseStatusException.class, () -> ips.update(me, entry.getId(), null, null, null, EntryStatus.DISABLED, null));
        assertThrows(ResponseStatusException.class, () -> ips.update(me, entry.getId(), "203.0.200.0/24", null, null, null, null));
        assertEquals(1, ipRepo.count());
    }

    @Test void canRemoveAnEntryWhenAnotherCoversYou() {
        var me = ctx("dev-admin@example.com", OFFICE_IP, AdminRole.ADMIN, false);
        var a = ips.add(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), "198.51.100.0/24", "a", null, null);
        ips.add(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), OFFICE_IP, "b", null, null);
        assertDoesNotThrow(() -> ips.delete(me, a.getId(), null));
    }

    @Test void bootstrapAddressAlwaysCoversTheBootstrapAdmin() {
        var entry = ips.add(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), BOOT_IP, "dup", null, null);
        assertDoesNotThrow(() -> ips.delete(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), entry.getId(), null));
    }

    @Test void cannotWeakenOrRemoveYourOwnEmailEntry() {
        var admin = ctx("me@example.com", OFFICE_IP, AdminRole.ADMIN, false);
        var root = ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true);
        var mine = emails.add(root, EntryKind.EMAIL, "me@example.com", AdminRole.ADMIN, null, null);
        assertThrows(ResponseStatusException.class, () -> emails.delete(admin, mine.getId(), null));
        assertThrows(ResponseStatusException.class, () -> emails.update(admin, mine.getId(), AdminRole.READ_ONLY, null, null, null));
        assertThrows(ResponseStatusException.class, () -> emails.update(admin, mine.getId(), null, EntryStatus.DISABLED, null, null));
        assertThrows(ResponseStatusException.class, () -> emails.deactivateEmail(admin, "me@example.com"));
    }

    @Test void bootstrapAdminCannotBeDisabledThroughTheConsole() {
        var other = ctx("other@example.com", OFFICE_IP, AdminRole.ADMIN, false);
        assertThrows(ResponseStatusException.class, () -> emails.deactivateEmail(other, "root@example.com"));
    }

    @Test void rejectsBadEmailsAndDomains() {
        var root = ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true);
        for (String bad : new String[]{"nope", "a@b", "*@example.com", "a b@example.com", ""})
            assertThrows(ResponseStatusException.class, () -> emails.add(root, EntryKind.EMAIL, bad, AdminRole.READ_ONLY, null, null), bad);
        for (String bad : new String[]{"@example.com", "*.example.com", "example", "a@example.com"})
            assertThrows(ResponseStatusException.class, () -> emails.add(root, EntryKind.DOMAIN, bad, AdminRole.READ_ONLY, null, null), bad);
    }

    @Test void duplicatesAndPastExpiryAreRejected() {
        var root = ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true);
        ips.add(root, "198.51.100.0/24", "x", null, null);
        assertThrows(ResponseStatusException.class, () -> ips.add(root, "198.51.100.77/24", "dup", null, null));
        assertThrows(ResponseStatusException.class, () -> ips.add(root, "198.51.101.0/24", "old", java.time.Instant.now().minusSeconds(60), null));
    }

    // ---- audit chain -------------------------------------------------------------------------

    private void threeEvents() {
        var c = ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true);
        for (int i = 0; i < 3; i++) audit.record(c, "TEST_EVENT", "T", "id" + i, "OP", Map.of("n", i), Map.of("n", i + 1), "because " + i);
    }

    @Test void auditChainVerifiesWhenUntouched() {
        threeEvents();
        var v = audit.verify();
        assertTrue(v.valid());
        assertEquals(3, v.checked());
        var rows = auditRepo.findAllByOrderByIdAsc(org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertNull(rows.get(0).getPrevHash());
        assertEquals(rows.get(0).getEventHash(), rows.get(1).getPrevHash());
    }

    @Test void editingAnEventIsDetected() {
        threeEvents();
        Long id = jdbc.queryForObject("select id from admin_audit_events order by id limit 1 offset 1", Long.class);
        jdbc.update("update admin_audit_events set reason = 'rewritten' where id = ?", id);
        var v = audit.verify();
        assertFalse(v.valid());
        assertEquals(id, v.firstBadId());
    }

    @Test void deletingAnEventIsDetected() {
        threeEvents();
        Long id = jdbc.queryForObject("select id from admin_audit_events order by id limit 1 offset 1", Long.class);
        jdbc.update("delete from admin_audit_events where id = ?", id);
        var v = audit.verify();
        assertFalse(v.valid());
    }

    @Test void auditRowsAreImmutableThroughTheApplication() {
        threeEvents();
        var first = auditRepo.findAllByOrderByIdAsc(org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0);
        // the repository exposes no update/delete at all
        assertTrue(java.util.Arrays.stream(AdminAuditRepositoryProbe.methods()).noneMatch(n -> n.startsWith("delete") || n.startsWith("update")));
        assertNotNull(first.getEventHash());
    }

    @Test void secretLookingValuesNeverReachTheAudit() {
        var c = ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true);
        var e = audit.record(c, "CONFIG", "CFG", "x", "UPDATE",
            Map.of("apiKey", "sk-live-AAAA", "db_password", "hunter2", "name", "ok"),
            Map.of("nested", Map.of("clientSecret", "zzz", "note", "line1\nline2")), "r");
        String all = e.getBeforeValue() + e.getAfterValue();
        assertFalse(all.contains("sk-live-AAAA"));
        assertFalse(all.contains("hunter2"));
        assertFalse(all.contains("zzz"));
        assertTrue(all.contains(Redactor.MASK));
        assertTrue(e.getBeforeValue().contains("\"name\":\"ok\""));
        assertFalse(e.getAfterValue().contains("\\n"), "control characters are neutralised");
    }

    @Test void adminActionsAreAuditedWithActorAndReason() {
        var root = ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true);
        ips.add(root, "198.51.100.0/24", "office", null, "new office");
        var rows = auditRepo.findAllByOrderByIdAsc(org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertEquals(1, rows.size());
        assertEquals("IP_ALLOWLIST_ADDED", rows.get(0).getAction());
        assertEquals("root@example.com", rows.get(0).getActorEmail());
        assertEquals("new office", rows.get(0).getReason());
        assertEquals("DEV", rows.get(0).getEnvironment());
        assertTrue(rows.get(0).getAfterValue().contains("198.51.100.0/24"));
    }

    // ---- lockdown + emergency ----------------------------------------------------------------

    @Test void lockdownDeniesEveryoneExceptBootstrapAdminReleasing() throws Exception {
        allowIp("198.51.100.0/24");
        allowEmail("adm@example.com", AdminRole.ADMIN);
        assertTrue(call("GET", "/api/admin/me", OFFICE_IP, "adm@example.com").reachedController());

        lockdown.engage(ctx("adm@example.com", OFFICE_IP, AdminRole.ADMIN, false), "incident");
        assertEquals(503, call("GET", "/api/admin/me", OFFICE_IP, "adm@example.com").status());
        assertEquals(503, call("GET", "/api/admin/me", BOOT_IP, "root@example.com").status());
        // a non-bootstrap admin cannot reach the release endpoint
        assertEquals(503, call("POST", "/api/admin/security/lockdown/release", OFFICE_IP, "adm@example.com").status());
        // a bootstrap admin on a non-bootstrap address cannot either
        assertEquals(503, call("POST", "/api/admin/security/lockdown/release", OFFICE_IP, "root@example.com").status());
        // the bootstrap admin on a bootstrap address can
        assertTrue(call("POST", "/api/admin/security/lockdown/release", BOOT_IP, "root@example.com").reachedController());
        assertThrows(ResponseStatusException.class, () -> lockdown.release(ctx("adm@example.com", OFFICE_IP, AdminRole.ADMIN, false), null));
        lockdown.release(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), "all clear");
        assertTrue(call("GET", "/api/admin/me", OFFICE_IP, "adm@example.com").reachedController());
    }

    @Test void lockdownIsAudited() {
        lockdown.engage(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), "drill");
        assertTrue(auditRepo.findAllByOrderByIdAsc(org.springframework.data.domain.PageRequest.of(0, 5)).getContent()
            .stream().anyMatch(e -> e.getAction().equals("LOCKDOWN_ENGAGED") && "drill".equals(e.getReason())));
    }

    @Test void accountDisableDeactivatesEntryAndBlocksAccessImmediately() throws Exception {
        allowIp("198.51.100.0/24");
        allowEmail("dev@example.com", AdminRole.DEVELOPER);
        assertTrue(call("GET", "/api/admin/me", OFFICE_IP, "dev@example.com").reachedController());
        lockdown.disableAccount(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), "dev@example.com", "left the team");
        assertEquals(403, call("GET", "/api/admin/me", OFFICE_IP, "dev@example.com").status());
    }

    @Test void emergencyIpDisableRemovesAccess() throws Exception {
        allowIp(BOOT_IP + "/32");
        var e = ips.add(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), "198.51.100.0/24", "office", null, null);
        allowEmail("dev@example.com", AdminRole.DEVELOPER);
        ips.disable(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), e.getId(), "compromised");
        assertEquals(403, call("GET", "/api/admin/me", OFFICE_IP, "dev@example.com").status());
    }

    // ---- misc --------------------------------------------------------------------------------

    @Test void mutationsInDevDoNotNeedConfirmationButAWrongOneIsRefused() throws Exception {
        assertTrue(call("POST", "/api/admin/ip-allowlist", BOOT_IP, "root@example.com").reachedController());
        assertEquals(412, call("POST", "/api/admin/ip-allowlist", BOOT_IP, "root@example.com", "X-Confirm-Environment", "PRODUCTION").status());
        assertTrue(call("POST", "/api/admin/ip-allowlist", BOOT_IP, "root@example.com", "X-Confirm-Environment", "DEV").reachedController());
    }

    @Test void environmentParsingFailsToTheStrictestMode() {
        assertEquals(AdminEnvironment.PRODUCTION, AdminEnvironment.parse("prod-ish"));
        assertEquals(AdminEnvironment.DEV, AdminEnvironment.parse(""));
        assertEquals(AdminEnvironment.STAGE, AdminEnvironment.parse(" stage "));
    }

    @Test void rolesAndPermissions() {
        assertTrue(com.marketai.admin.security.Permission.VIEW.grantedTo(AdminRole.READ_ONLY));
        assertFalse(com.marketai.admin.security.Permission.EDIT.grantedTo(AdminRole.READ_ONLY));
        assertTrue(com.marketai.admin.security.Permission.EDIT.grantedTo(AdminRole.DEVELOPER));
        assertFalse(com.marketai.admin.security.Permission.MANAGE_ACCESS.grantedTo(AdminRole.DEVELOPER));
        assertTrue(com.marketai.admin.security.Permission.MANAGE_ACCESS.grantedTo(AdminRole.ADMIN));
        assertFalse(com.marketai.admin.security.Permission.VIEW.grantedTo(null));
    }

    /** Lists the repository's method names so the test can prove it offers no update or delete. */
    static final class AdminAuditRepositoryProbe {
        static String[] methods() {
            return java.util.Arrays.stream(com.marketai.admin.repo.AdminAuditRepository.class.getMethods())
                .map(java.lang.reflect.Method::getName).toArray(String[]::new);
        }
    }
}
