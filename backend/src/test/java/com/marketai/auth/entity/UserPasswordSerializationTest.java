package com.marketai.auth.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a live-verified leak: several entities carry a {@code User user} FK
 * (CashAccount, LedgerTransfer, RecurringInvestment, ...), and at least one controller
 * (LedgerController, before it was fixed to use response DTOs) returned such an entity
 * directly — which serialized the bcrypt password hash straight into the HTTP response body.
 * {@code @JsonIgnore} on {@link User#getPassword()} is the defense-in-depth backstop: even if
 * a future endpoint makes the same mistake of returning a User-carrying entity directly, the
 * password can never leak through Jackson serialization.
 */
class UserPasswordSerializationTest {

    @Test
    void passwordIsNeverSerialized() throws Exception {
        User user = User.builder()
            .id(1L).name("Test User").email("test@example.com")
            .password("$2a$12$verysecrethash")
            .build();

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(user);

        assertThat(json).doesNotContain("verysecrethash");
        assertThat(json).doesNotContain("\"password\"");
        // Sanity check the fix isn't overly broad — ordinary fields must still serialize.
        assertThat(json).contains("test@example.com");
    }
}
