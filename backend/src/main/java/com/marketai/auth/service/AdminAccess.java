package com.marketai.auth.service;

import com.marketai.auth.entity.Role;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may change installation-wide settings (the LLM configuration decides where every user's
 * documents are sent). An admin is anyone listed in {@code app.admin.emails} (ADMIN_EMAILS), or
 * holding ROLE_ADMIN. With neither configured, only the first account created — whoever set the
 * installation up — is; nobody else gets in by default.
 */
@Component
public class AdminAccess {

    private final UserRepository users;
    private final Set<String> adminEmails;

    public AdminAccess(UserRepository users, @Value("${app.admin.emails:}") String adminEmails) {
        this.users = users;
        this.adminEmails = Arrays.stream(adminEmails.split(","))
            .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(User user) {
        if (user == null) return false;
        if (user.getRoles() != null && user.getRoles().stream().anyMatch(r -> r.getName() == Role.RoleName.ROLE_ADMIN)) {
            return true;
        }
        if (!adminEmails.isEmpty()) {
            return user.getEmail() != null && adminEmails.contains(user.getEmail().toLowerCase(Locale.ROOT));
        }
        return users.findFirstByOrderByIdAsc().map(first -> first.getId().equals(user.getId())).orElse(false);
    }

    public void require(User user) {
        if (!isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can change this setting.");
        }
    }
}
