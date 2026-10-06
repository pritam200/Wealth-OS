package com.marketai.admin;

import com.marketai.admin.domain.AdminRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties = "app.admin.environment=PRODUCTION")
class AdminProductionGuardTest extends AdminTestSupport {

    @Test void readsNeedNoConfirmation() throws Exception {
        assertTrue(call("GET", "/api/admin/me", BOOT_IP, "root@example.com").reachedController());
    }

    @Test void changesWithoutConfirmationAreRefused() throws Exception {
        assertEquals(412, call("POST", "/api/admin/ip-allowlist", BOOT_IP, "root@example.com").status());
        assertEquals(412, call("DELETE", "/api/admin/ip-allowlist/1", BOOT_IP, "root@example.com").status());
        assertEquals(412, call("PUT", "/api/admin/email-allowlist/1", BOOT_IP, "root@example.com", "X-Confirm-Environment", "DEV").status());
    }

    @Test void confirmedChangesPass() throws Exception {
        var o = call("POST", "/api/admin/ip-allowlist", BOOT_IP, "root@example.com", "X-Confirm-Environment", "PRODUCTION");
        assertTrue(o.reachedController());
        assertEquals(com.marketai.admin.domain.AdminEnvironment.PRODUCTION, o.ctx().environment());
    }

    @Test void ipEntriesBelongToOneEnvironment() throws Exception {
        var e = new com.marketai.admin.domain.IpAllowEntry();
        e.setCidr("198.51.100.0/24"); e.setEnvironment(com.marketai.admin.domain.AdminEnvironment.DEV);
        ipRepo.save(e);
        allowEmail("dev@example.com", AdminRole.DEVELOPER);
        assertEquals(403, call("GET", "/api/admin/me", OFFICE_IP, "dev@example.com").status(), "a DEV entry must not open PRODUCTION");
    }
}
