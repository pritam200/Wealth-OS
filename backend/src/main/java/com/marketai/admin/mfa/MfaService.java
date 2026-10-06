package com.marketai.admin.mfa;

import com.marketai.admin.domain.AdminMfa;
import com.marketai.admin.repo.AdminMfaRepository;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.security.AdminSettings;
import com.marketai.admin.service.AdminAuditService;
import com.marketai.gmail.security.PasswordCipher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TOTP step-up. Enrolling proves the authenticator works; after that, sensitive operations need a
 * code entered within the last {@value #FRESH_SECONDS} seconds. Step-up state is held on the server
 * (in memory, per administrator), so a restart simply asks again.
 */
@Service
public class MfaService {
    public static final long FRESH_SECONDS = 15 * 60;
    public static final String MFA_REQUIRED = "MFA_REQUIRED";

    private final AdminMfaRepository repo;
    private final PasswordCipher cipher;
    private final AdminSettings settings;
    private final AdminAuditService audit;
    private final Map<String, Instant> verifiedUntil = new ConcurrentHashMap<>();

    public MfaService(AdminMfaRepository repo, PasswordCipher cipher, AdminSettings settings, AdminAuditService audit) {
        this.repo = repo; this.cipher = cipher; this.settings = settings; this.audit = audit;
    }

    public record Enrolment(String secret, String otpauthUri) {}

    public boolean required() { return settings.mfaRequired(); }

    public boolean enrolled(String email) { return repo.findById(email).map(AdminMfa::isEnabled).orElse(false); }

    public boolean fresh(String email) {
        Instant until = verifiedUntil.get(email);
        return until != null && until.isAfter(Instant.now());
    }

    /** Starts (or restarts, if not yet confirmed) enrolment. The secret is shown exactly once. */
    public Enrolment enroll(AdminContext ctx) {
        if (enrolled(ctx.email())) throw new ResponseStatusException(HttpStatus.CONFLICT, "An authenticator is already set up for this account.");
        String secret = Totp.newSecret();
        AdminMfa m = repo.findById(ctx.email()).orElseGet(AdminMfa::new);
        m.setEmail(ctx.email()); m.setSecretEnc(cipher.encrypt(secret)); m.setEnabled(false); m.setLastCounter(0); m.setCreatedAt(Instant.now());
        repo.save(m);
        String issuer = URLEncoder.encode("MarketAI " + ctx.environment(), StandardCharsets.UTF_8).replace("+", "%20");
        String label = issuer + ":" + URLEncoder.encode(ctx.email(), StandardCharsets.UTF_8);
        return new Enrolment(secret, "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + issuer + "&digits=6&period=30");
    }

    /** Confirms enrolment with the first code, or steps up an enrolled admin. */
    public void verify(AdminContext ctx, String code) {
        AdminMfa m = repo.findById(ctx.email()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Set up an authenticator first."));
        long c = Totp.verify(cipher.decrypt(m.getSecretEnc()), code, Totp.counter(Instant.now().getEpochSecond()), m.getLastCounter());
        if (c < 0) {
            audit.record(ctx, "MFA_FAILED", "MFA", ctx.email(), "VERIFY", null, null, null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That code is not valid. Check the clock on your phone and try the next code.");
        }
        boolean activating = !m.isEnabled();
        m.setLastCounter(c); m.setEnabled(true);
        repo.save(m);                                                   // the counter blocks replaying this code
        verifiedUntil.put(ctx.email(), Instant.now().plusSeconds(FRESH_SECONDS));
        audit.record(ctx, activating ? "MFA_ENROLLED" : "MFA_STEP_UP", "MFA", ctx.email(), "VERIFY", null, null, null);
    }

    /** Throws MFA_REQUIRED when the deployment demands MFA and this admin has not just proven it. */
    public void requireFresh(AdminContext ctx) {
        if (!required()) return;
        if (!fresh(ctx.email())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, MFA_REQUIRED);
    }

    public void forget(String email) { verifiedUntil.remove(email); }

    /** Admin removes someone's authenticator (lost phone). They must re-enrol. */
    public void reset(AdminContext ctx, String email) {
        repo.deleteById(email.toLowerCase());
        forget(email.toLowerCase());
        audit.record(ctx, "MFA_RESET", "MFA", email.toLowerCase(), "DELETE", null, null, null);
    }
}
