package com.marketai.admin;

import com.marketai.admin.domain.*;
import com.marketai.admin.repo.*;
import com.marketai.admin.security.AdminAccessFilter;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** The whole admin module over an in-memory database, with real transactions. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AdminTestSupport.Wiring.class)
@TestPropertySource(properties = {
    "app.admin.environment=DEV",
    "app.admin.emails=root@example.com",
    "app.admin.bootstrap-ips=203.0.113.10",
    "app.admin.trusted-proxies=10.0.0.0/8",
    "app.admin.rate-limit-per-minute=1000",
    "app.security.pdf-password-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
abstract class AdminTestSupport {

    @Configuration
    @ComponentScan("com.marketai.admin")
    @Import({JacksonAutoConfiguration.class, com.marketai.gmail.security.PasswordCipher.class})
    static class Wiring {}

    static final String BOOT_IP = "203.0.113.10";
    static final String OFFICE_IP = "198.51.100.7";
    static final String OUTSIDER_IP = "192.0.2.99";

    @Autowired AdminAccessFilter filter;
    @Autowired IpAllowlistService ips;
    @Autowired EmailAllowlistService emails;
    @Autowired LockdownService lockdown;
    @Autowired AdminAuditService audit;
    @Autowired IpAllowRepository ipRepo;
    @Autowired EmailAllowRepository emailRepo;
    @Autowired AdminAuditRepository auditRepo;
    @Autowired AdminRequestLogRepository logRepo;
    @Autowired AdminLockdownRepository lockRepo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate testJdbc;

    @BeforeEach @AfterEach
    void clean() {
        ipRepo.deleteAllInBatch(); emailRepo.deleteAllInBatch(); logRepo.deleteAllInBatch(); lockRepo.deleteAllInBatch();
        testJdbc.update("delete from admin_audit_events");   // the repository deliberately cannot
        SecurityContextHolder.clearContext();
    }

    record Outcome(MockHttpServletResponse response, MockHttpServletRequest request, boolean reachedController) {
        int status() { return response.getStatus(); }
        AdminContext ctx() { return (AdminContext) request.getAttribute(AdminContext.ATTR); }
    }

    /** Runs one request through the access filter. {@code email == null} means not authenticated. */
    Outcome call(String method, String uri, String peerIp, String email, String... headers) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        req.setRemoteAddr(peerIp);
        for (int i = 0; i + 1 < headers.length; i += 2) req.addHeader(headers[i], headers[i + 1]);
        SecurityContextHolder.clearContext();
        if (email != null) SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return new Outcome(res, req, chain.getRequest() != null);
    }

    AdminContext ctx(String email, String ip, AdminRole role, boolean bootstrap) {
        return new AdminContext("req-1", ip, email, role, AdminEnvironment.DEV, bootstrap);
    }

    void allowIp(String cidr) { ips.add(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), cidr, "test", null, null); }

    void allowEmail(String email, AdminRole role) {
        emails.add(ctx("root@example.com", BOOT_IP, AdminRole.ADMIN, true), EntryKind.EMAIL, email, role, null, null);
    }

    Instant inFuture() { return Instant.now().plusSeconds(3600); }
}
