package com.marketai.auth.service;

import com.marketai.auth.entity.Role;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAccessTest {

    private static User user(long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    @Test
    @DisplayName("with no admins configured, only the first account may change installation settings")
    void firstAccountByDefault() {
        UserRepository users = mock(UserRepository.class);
        when(users.findFirstByOrderByIdAsc()).thenReturn(Optional.of(user(1, "owner@example.test")));
        AdminAccess access = new AdminAccess(users, "");

        assertThat(access.isAdmin(user(1, "owner@example.test"))).isTrue();
        assertThat(access.isAdmin(user(2, "family@example.test"))).isFalse();
        assertThatThrownBy(() -> access.require(user(2, "family@example.test"))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("ADMIN_EMAILS replaces the default, and ROLE_ADMIN always counts")
    void listedEmailsAndRole() {
        UserRepository users = mock(UserRepository.class);
        when(users.findFirstByOrderByIdAsc()).thenReturn(Optional.of(user(1, "owner@example.test")));
        AdminAccess access = new AdminAccess(users, " Admin@Example.test ");

        assertThat(access.isAdmin(user(3, "admin@example.test"))).isTrue();
        assertThat(access.isAdmin(user(1, "owner@example.test"))).isFalse();
        User roleHolder = user(4, "ops@example.test");
        roleHolder.setRoles(Set.of(new Role(Role.RoleName.ROLE_ADMIN)));
        assertThat(access.isAdmin(roleHolder)).isTrue();
    }
}
