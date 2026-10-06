package com.marketai.admin.service;

import com.marketai.admin.domain.AdminLockdown;
import com.marketai.admin.repo.AdminLockdownRepository;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.security.AdminSettings;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.RefreshTokenRepository;
import com.marketai.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/** Emergency controls. Engaging lockdown denies every admin request; only a bootstrap admin on a bootstrap IP can release it. */
@Service
public class LockdownService {

    private final AdminLockdownRepository repo;
    private final AdminSettings settings;
    private final AdminAuditService audit;
    private final EmailAllowlistService emails;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;

    public LockdownService(AdminLockdownRepository repo, AdminSettings settings, AdminAuditService audit,
                           EmailAllowlistService emails, UserRepository users, RefreshTokenRepository refreshTokens) {
        this.repo = repo; this.settings = settings; this.audit = audit; this.emails = emails;
        this.users = users; this.refreshTokens = refreshTokens;
    }

    public AdminLockdown status() { return repo.findById(AdminLockdown.ID).orElseGet(AdminLockdown::new); }

    public boolean isEngaged() { return status().isEngaged(); }

    public AdminLockdown engage(AdminContext ctx, String reason) {
        AdminLockdown l = status();
        l.setId(AdminLockdown.ID); l.setEngaged(true); l.setEngagedBy(ctx.email()); l.setEngagedAt(Instant.now());
        l.setReason(Redactor.clean(reason));
        l = repo.save(l);
        audit.record(ctx, "LOCKDOWN_ENGAGED", "SECURITY", "lockdown", "ENGAGE", Map.of("engaged", false), Map.of("engaged", true), reason);
        return l;
    }

    public AdminLockdown release(AdminContext ctx, String reason) {
        if (!ctx.bootstrap() || !settings.isBootstrapIp(ctx.ip()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a bootstrap administrator on a bootstrap address can release a lockdown.");
        AdminLockdown l = status();
        l.setId(AdminLockdown.ID); l.setEngaged(false);
        l = repo.save(l);
        audit.record(ctx, "LOCKDOWN_RELEASED", "SECURITY", "lockdown", "RELEASE", Map.of("engaged", true), Map.of("engaged", false), reason);
        return l;
    }

    /** Deactivates the allowlist entry and revokes every refresh token of the account. */
    @Transactional
    public void disableAccount(AdminContext ctx, String email, String reason) {
        boolean entryChanged = emails.deactivateEmail(ctx, email);
        User user = users.findByEmail(email.trim()).orElse(null);
        if (user != null) refreshTokens.revokeAllUserTokens(user);
        audit.record(ctx, "ACCOUNT_DISABLED", "ACCOUNT", email.trim().toLowerCase(), "DISABLE", null,
            Map.of("allowlistEntryDeactivated", entryChanged, "sessionsRevoked", user != null), reason);
    }
}
