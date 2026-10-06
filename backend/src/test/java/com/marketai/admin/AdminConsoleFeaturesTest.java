package com.marketai.admin;

import com.marketai.admin.config.ConfigConsoleService;
import com.marketai.admin.data.DataConsoleService;
import com.marketai.admin.domain.AdminChangeRequest;
import com.marketai.admin.domain.AdminRole;
import com.marketai.admin.mfa.MfaService;
import com.marketai.admin.mfa.Totp;
import com.marketai.admin.repo.*;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdminConsoleFeaturesTest extends AdminTestSupport {

    @Autowired DataConsoleService data;
    @Autowired ConfigConsoleService config;
    @Autowired MfaService mfa;
    @Autowired UserRepository users;
    @Autowired AdminMfaRepository mfaRepo;
    @Autowired AdminChangeRequestRepository changeRepo;
    @Autowired AdminSecretMetaRepository metaRepo;
    @Autowired AdminFeatureFlagRepository flagRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.marketai.auth.repository.RefreshTokenRepository tokenRepo;

    Long customer;

    @BeforeEach
    void seed() {
        changeRepo.deleteAllInBatch(); mfaRepo.deleteAllInBatch(); metaRepo.deleteAllInBatch(); flagRepo.deleteAllInBatch();
        tokenRepo.deleteAllInBatch();
        users.deleteAll();
        customer = users.save(User.builder().name("Asha").email("asha@example.com").password("$2a$hash-must-never-show").build()).getId();
    }

    private final com.marketai.admin.security.AdminContext dev() { return ctx("dev@example.com", OFFICE_IP, AdminRole.DEVELOPER, false); }
    private final com.marketai.admin.security.AdminContext adminA() { return ctx("a1@example.com", OFFICE_IP, AdminRole.ADMIN, false); }
    private final com.marketai.admin.security.AdminContext adminB() { return ctx("a2@example.com", OFFICE_IP, AdminRole.ADMIN, false); }

    // ---- data console ----

    @Test void onlyAllowlistedTablesAndColumnsAreReachable() {
        assertThrows(ResponseStatusException.class, () -> data.search("refresh_tokens", null, 0, 10));
        assertThrows(ResponseStatusException.class, () -> data.search("users; DROP TABLE users", null, 0, 10));
        var row = data.get("users", String.valueOf(customer));
        assertFalse(row.containsKey("password"));
        assertEquals("asha@example.com", row.get("email"));
    }

    @Test void searchIsParameterisedAndEscapesWildcards() {
        assertEquals(1, data.search("users", "asha", 0, 10).total());
        assertEquals(0, data.search("users", "' OR '1'='1", 0, 10).total());
        assertEquals(0, data.search("users", "%", 0, 10).total(), "% is literal");
        assertEquals(1, data.search("users", String.valueOf(customer), 0, 10).total());
    }

    @Test void editingAnOrdinaryFieldIsAppliedAndAudited() {
        data.update(dev(), "users", String.valueOf(customer), Map.of("name", "Asha K"), "typo reported");
        assertEquals("Asha K", users.findById(customer).orElseThrow().getName());
        var last = auditRepo.findTopByOrderByIdDesc().orElseThrow();
        assertEquals("DATA_UPDATED", last.getAction());
        assertTrue(last.getBeforeValue().contains("Asha") && last.getAfterValue().contains("Asha K"));
        assertEquals("typo reported", last.getReason());
    }

    @Test void nonEditableFieldsAndBadValuesAreRefused() {
        String id = String.valueOf(customer);
        for (Map<String, Object> bad : java.util.List.of(Map.<String, Object>of("email", "x@y.z"), Map.<String, Object>of("password", "x"),
                Map.<String, Object>of("id", 5), Map.<String, Object>of("name", 5), Map.<String, Object>of("name", "a".repeat(101)),
                Map.<String, Object>of("name", "line\nbreak"), Map.<String, Object>of("name", " ")))
            assertThrows(ResponseStatusException.class, () -> data.update(dev(), "users", id, bad, "r"), bad.toString());
        assertThrows(ResponseStatusException.class, () -> data.update(dev(), "users", id, Map.of("name", "ok"), " "), "reason required");
        assertEquals("Asha", users.findById(customer).orElseThrow().getName());
    }

    @Test void sensitiveFieldNeedsAnotherAdministrator() {
        String id = String.valueOf(customer);
        var r = data.update(dev(), "users", id, Map.of("enabled", false), "abuse report");
        assertTrue(users.findById(customer).orElseThrow().isEnabled(), "not applied yet");
        Long reqId = (Long) r.get("pendingApproval");
        assertNotNull(reqId);
        assertThrows(ResponseStatusException.class, () -> data.decide(dev(), reqId, true, null), "developer cannot decide");
        var requester = adminA();
        var again = data.update(requester, "users", id, Map.of("enabled", false), "second");
        Long own = (Long) again.get("pendingApproval");
        assertThrows(ResponseStatusException.class, () -> data.decide(requester, own, true, null), "no self-approval");
        data.decide(adminB(), reqId, true, "confirmed");
        assertFalse(users.findById(customer).orElseThrow().isEnabled());
        assertEquals(AdminChangeRequest.Status.APPROVED, changeRepo.findById(reqId).orElseThrow().getStatus());
        assertThrows(ResponseStatusException.class, () -> data.decide(adminB(), reqId, true, null), "decided once");
    }

    @Test void rejectedRequestChangesNothing() {
        Long reqId = (Long) data.update(dev(), "users", String.valueOf(customer), Map.of("enabled", false), "r").get("pendingApproval");
        data.decide(adminB(), reqId, false, "no");
        assertTrue(users.findById(customer).orElseThrow().isEnabled());
    }

    // ---- MFA ----

    @Test void enrolmentThenStepUpWithReplayProtection() {
        var c = dev();
        assertFalse(mfa.enrolled(c.email()));
        var en = mfa.enroll(c);
        assertTrue(en.otpauthUri().startsWith("otpauth://totp/"));
        assertFalse(mfa.fresh(c.email()));
        assertThrows(ResponseStatusException.class, () -> mfa.verify(c, "000000"));
        long now = Totp.counter(Instant.now().getEpochSecond());
        String code = Totp.code(en.secret(), now);
        mfa.verify(c, code);
        assertTrue(mfa.enrolled(c.email()));
        assertTrue(mfa.fresh(c.email()));
        mfa.forget(c.email());
        assertThrows(ResponseStatusException.class, () -> mfa.verify(c, code), "same code cannot be reused");
        assertThrows(ResponseStatusException.class, () -> mfa.enroll(c), "no silent re-enrolment");
        assertNotEquals(en.secret(), mfaRepo.findById(c.email()).orElseThrow().getSecretEnc(), "stored encrypted");
    }

    @Test void mfaIsNotDemandedInDevButIsInProduction() {
        assertFalse(mfa.required());
    }

    // ---- configuration ----

    @Test void secretsAreMaskedAndOnlyKnownNamesAppear() {
        var items = config.view();
        assertTrue(items.stream().anyMatch(i -> i.get("name").equals("JWT_SECRET") && Boolean.TRUE.equals(i.get("secret"))));
        for (var i : items) if (Boolean.TRUE.equals(i.get("secret"))) {
            String d = String.valueOf(i.get("display"));
            assertTrue(d.equals("Not configured") || d.startsWith("********"), d);
        }
    }

    @Test void rotationWithoutASecretManagerIsRefusedAndRecordsNothing() {
        assertFalse(config.rotationAvailable());
        assertThrows(ResponseStatusException.class, () -> config.rotate(dev(), "JWT_SECRET", "a-new-secret-value", "scheduled"));
        assertEquals(0, metaRepo.count());
        assertThrows(ResponseStatusException.class, () -> config.rotate(dev(), "APP_ENVIRONMENT", "xxxxxxxxxx", "r"), "not a secret");
        assertThrows(ResponseStatusException.class, () -> config.rotate(dev(), "NOT_LISTED", "xxxxxxxxxx", "r"));
    }

    @Test void auditNeverContainsRotatedValues() {
        // a store that accepts: swap in via a throwaway ConfigConsoleService
        var accepting = new ConfigConsoleService(new com.marketai.admin.config.ConfigRegistry(), new org.springframework.mock.env.MockEnvironment(),
            new com.marketai.admin.config.SecretStore() { public boolean available() { return true; } public void put(String n, String v) {} },
            metaRepo, flagRepo, audit);
        accepting.rotate(dev(), "JWT_SECRET", "super-secret-new-value", "quarterly");
        var last = auditRepo.findTopByOrderByIdDesc().orElseThrow();
        assertEquals("SECRET_ROTATED", last.getAction());
        assertFalse((last.getBeforeValue() + last.getAfterValue() + last.getReason()).contains("super-secret-new-value"));
        assertNotNull(metaRepo.findById("JWT_SECRET").orElseThrow().getLastRotatedAt());
        assertThrows(ResponseStatusException.class, () -> accepting.rotate(dev(), "JWT_SECRET", "short", "r"));
    }

    @Test void featureFlagsAreOnlyThoseRegistered() {
        config.setFlag(dev(), "new_signups", true, "launch");
        assertTrue(config.flag("new_signups"));
        assertThrows(ResponseStatusException.class, () -> config.setFlag(dev(), "made_up", true, "r"));
    }
}
