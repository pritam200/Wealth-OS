package com.marketai.admin.security;

import com.marketai.admin.domain.AdminEnvironment;
import com.marketai.admin.net.Cidr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bootstrap access that lives in the environment rather than the database, so a bad database
 * change can never lock every administrator out and no database row can mint a super-admin.
 */
@Component
public class AdminSettings {

    private final AdminEnvironment environment;
    private final Set<String> bootstrapEmails;
    private final List<Cidr> bootstrapIps;
    private final int rateLimitPerMinute;
    private final boolean mfaRequired;

    public AdminSettings(@Value("${app.admin.environment:DEV}") String environment,
                         @Value("${app.admin.emails:}") String emails,
                         @Value("${app.admin.bootstrap-ips:}") String bootstrapIps,
                         @Value("${app.admin.rate-limit-per-minute:120}") int rateLimitPerMinute,
                         @Value("${app.admin.mfa-required:}") String mfaRequired) {
        this.environment = AdminEnvironment.parse(environment);
        this.bootstrapEmails = Arrays.stream(emails.split(","))
            .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        this.bootstrapIps = Arrays.stream(bootstrapIps.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).map(Cidr::parse).toList();
        this.rateLimitPerMinute = rateLimitPerMinute;
        // Unset = required exactly when this is PRODUCTION.
        this.mfaRequired = mfaRequired == null || mfaRequired.isBlank() ? this.environment == AdminEnvironment.PRODUCTION : Boolean.parseBoolean(mfaRequired.trim());
    }

    public AdminEnvironment environment() { return environment; }
    public boolean mfaRequired() { return mfaRequired; }
    public int rateLimitPerMinute() { return rateLimitPerMinute; }
    public boolean isBootstrapEmail(String email) { return email != null && bootstrapEmails.contains(email.toLowerCase(Locale.ROOT)); }
    public boolean isBootstrapIp(String ip) { return ip != null && bootstrapIps.stream().anyMatch(c -> c.contains(ip)); }
    public boolean hasBootstrapAdmins() { return !bootstrapEmails.isEmpty(); }
}
