package com.marketai.auth;

import com.marketai.admin.security.AdminSettings;
import com.marketai.auth.entity.RefreshToken;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.RefreshTokenRepository;
import com.marketai.auth.repository.UserRepository;
import com.marketai.auth.service.AccountDeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;


import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AccountDeletionService.class, AdminSettings.class, AccountDeletionTest.Cfg.class})
@TestPropertySource(properties = "app.admin.emails=root@example.com")
class AccountDeletionTest {

    @org.springframework.boot.test.context.TestConfiguration static class Cfg { @Bean PasswordEncoder enc() { return new BCryptPasswordEncoder(4); } }

    @Autowired AccountDeletionService service;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired PasswordEncoder enc;
    @Autowired JdbcTemplate jdbc;

    private User make(String email) {
        User u = users.save(User.builder().name("N").email(email).password(enc.encode("secret-pw")).build());
        tokens.save(RefreshToken.builder().token("t-" + email).user(u).expiresAt(java.time.Instant.now().plusSeconds(86400)).build());
        return u;
    }

    @Test void deletesTheUserAndTheirDataButNotAnyoneElses() {
        make("a@example.com"); make("b@example.com");
        service.delete("a@example.com", "secret-pw");
        assertTrue(users.findByEmail("a@example.com").isEmpty());
        assertTrue(users.findByEmail("b@example.com").isPresent());
        assertEquals(1, tokens.count());
    }

    @Test void wrongPasswordDeletesNothing() {
        make("c@example.com");
        assertThrows(ResponseStatusException.class, () -> service.delete("c@example.com", "nope"));
        assertTrue(users.findByEmail("c@example.com").isPresent());
    }

    @Test void consoleBootstrapAdminCannotSelfDelete() {
        make("root@example.com");
        assertThrows(ResponseStatusException.class, () -> service.delete("root@example.com", "secret-pw"));
    }
}
