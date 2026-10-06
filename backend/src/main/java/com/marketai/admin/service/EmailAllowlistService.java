package com.marketai.admin.service;

import com.marketai.admin.domain.AdminRole;
import com.marketai.admin.domain.EmailAllowEntry;
import com.marketai.admin.domain.EntryKind;
import com.marketai.admin.domain.EntryStatus;
import com.marketai.admin.repo.EmailAllowRepository;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.security.AdminSettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class EmailAllowlistService {

    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+-]{1,64}@[a-z0-9.-]{1,120}\\.[a-z]{2,24}$");
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9.-]{1,120}\\.[a-z]{2,24}$");

    private final EmailAllowRepository repo;
    private final AdminSettings settings;
    private final AdminAuditService audit;

    public EmailAllowlistService(EmailAllowRepository repo, AdminSettings settings, AdminAuditService audit) {
        this.repo = repo; this.settings = settings; this.audit = audit;
    }

    /** The caller's role, or empty when they have no live entry. An exact email entry beats a domain entry; a domain entry is never ADMIN. */
    public Optional<AdminRole> roleFor(String email) {
        if (email == null) return Optional.empty();
        String e = email.toLowerCase(Locale.ROOT);
        if (settings.isBootstrapEmail(e)) return Optional.of(AdminRole.ADMIN);
        Instant now = Instant.now();
        Optional<AdminRole> exact = repo.findByKindAndValue(EntryKind.EMAIL, e).stream()
            .filter(x -> x.isLive(now)).map(EmailAllowEntry::getRole).findFirst();
        if (exact.isPresent()) return exact;
        int at = e.lastIndexOf('@');
        if (at < 0) return Optional.empty();
        return repo.findByKindAndValue(EntryKind.DOMAIN, e.substring(at + 1)).stream()
            .filter(x -> x.isLive(now)).map(EmailAllowEntry::getRole)
            .map(r -> r == AdminRole.ADMIN ? AdminRole.DEVELOPER : r).findFirst();
    }

    public List<EmailAllowEntry> list() { return repo.findAllByOrderByIdAsc(); }

    public EmailAllowEntry add(AdminContext ctx, EntryKind kind, String value, AdminRole role, Instant expiresAt, String reason) {
        String v = normalise(kind, value);
        if (kind == EntryKind.DOMAIN && role == AdminRole.ADMIN)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A whole domain cannot be given the ADMIN role. Add each administrator by email.");
        if (repo.existsByKindAndValue(kind, v)) throw new ResponseStatusException(HttpStatus.CONFLICT, "That entry already exists.");
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The expiry is in the past.");
        EmailAllowEntry e = new EmailAllowEntry();
        e.setKind(kind); e.setEmailOrDomain(v); e.setRole(role); e.setCreatedBy(ctx.email()); e.setCreatedAt(Instant.now()); e.setExpiresAt(expiresAt);
        e = repo.save(e);
        audit.record(ctx, "EMAIL_ALLOWLIST_ADDED", "EMAIL_ALLOWLIST", String.valueOf(e.getId()), "CREATE", null, snapshot(e), reason);
        return e;
    }

    public EmailAllowEntry update(AdminContext ctx, Long id, AdminRole role, EntryStatus status, Instant expiresAt, String reason) {
        EmailAllowEntry e = find(id);
        Map<String, Object> before = snapshot(e);
        AdminRole newRole = role == null ? e.getRole() : role;
        EntryStatus newStatus = status == null ? e.getStatus() : status;
        if (e.getKind() == EntryKind.DOMAIN && newRole == AdminRole.ADMIN)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A whole domain cannot be given the ADMIN role.");
        boolean loses = newStatus == EntryStatus.DISABLED || newRole != AdminRole.ADMIN
            || (expiresAt != null && expiresAt.isBefore(Instant.now()));
        if (loses) guard(ctx, e, "change");
        e.setRole(newRole); e.setStatus(newStatus); e.setExpiresAt(expiresAt); e.setUpdatedBy(ctx.email()); e.setUpdatedAt(Instant.now());
        e = repo.save(e);
        audit.record(ctx, "EMAIL_ALLOWLIST_UPDATED", "EMAIL_ALLOWLIST", String.valueOf(id), "UPDATE", before, snapshot(e), reason);
        return e;
    }

    public void delete(AdminContext ctx, Long id, String reason) {
        EmailAllowEntry e = find(id);
        guard(ctx, e, "remove");
        repo.delete(e);
        audit.record(ctx, "EMAIL_ALLOWLIST_REMOVED", "EMAIL_ALLOWLIST", String.valueOf(id), "DELETE", snapshot(e), null, reason);
    }

    /** Deactivates the person's entry (if any). Returns whether an entry was changed. */
    public boolean deactivateEmail(AdminContext ctx, String email) {
        String e = email.toLowerCase(Locale.ROOT).trim();
        if (settings.isBootstrapEmail(e))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This is a bootstrap administrator set in the server environment (ADMIN_EMAILS). Remove it there.");
        if (e.equals(ctx.email())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Refused: you cannot disable your own account.");
        boolean changed = false;
        for (EmailAllowEntry x : repo.findByKindAndValue(EntryKind.EMAIL, e)) {
            if (x.getStatus() == EntryStatus.ACTIVE) {
                x.setStatus(EntryStatus.DISABLED); x.setUpdatedBy(ctx.email()); x.setUpdatedAt(Instant.now()); repo.save(x); changed = true;
            }
        }
        return changed;
    }

    private void guard(AdminContext ctx, EmailAllowEntry e, String verb) {
        if (e.getKind() == EntryKind.EMAIL && e.getEmailOrDomain().equals(ctx.email()) && !ctx.bootstrap())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refused: you cannot " + verb + " your own access.");
        if (e.getRole() == AdminRole.ADMIN && e.isLive(Instant.now()) && !settings.hasBootstrapAdmins()) {
            long others = repo.findAllByOrderByIdAsc().stream()
                .filter(x -> !x.getId().equals(e.getId()) && x.getRole() == AdminRole.ADMIN && x.isLive(Instant.now())).count();
            if (others == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Refused: this is the last administrator.");
        }
    }

    private String normalise(EntryKind kind, String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        boolean ok = kind == EntryKind.EMAIL ? EMAIL.matcher(v).matches() : DOMAIN.matcher(v).matches();
        if (!ok) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            kind == EntryKind.EMAIL ? "Enter a valid email address." : "Enter a domain such as example.com (no @, no wildcards).");
        return v;
    }

    private EmailAllowEntry find(Long id) {
        return repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such entry."));
    }

    private static Map<String, Object> snapshot(EmailAllowEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emailOrDomain", e.getEmailOrDomain()); m.put("kind", e.getKind().name()); m.put("role", e.getRole().name());
        m.put("status", e.getStatus().name()); m.put("expiresAt", e.getExpiresAt() == null ? null : e.getExpiresAt().toString());
        return m;
    }
}
