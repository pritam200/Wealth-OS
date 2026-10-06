package com.marketai.admin.service;

import com.marketai.admin.domain.AdminEnvironment;
import com.marketai.admin.domain.EntryStatus;
import com.marketai.admin.domain.IpAllowEntry;
import com.marketai.admin.net.Cidr;
import com.marketai.admin.repo.IpAllowRepository;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.security.AdminSettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IpAllowlistService {

    private final IpAllowRepository repo;
    private final AdminSettings settings;
    private final AdminAuditService audit;

    public IpAllowlistService(IpAllowRepository repo, AdminSettings settings, AdminAuditService audit) {
        this.repo = repo; this.settings = settings; this.audit = audit;
    }

    /** Fail closed: unknown IP, no entries and no bootstrap IP all mean "not allowed". */
    public boolean isAllowed(String ip) { return isAllowedExcluding(ip, null); }

    private boolean isAllowedExcluding(String ip, Long excludedId) {
        if (ip == null) return false;
        if (settings.isBootstrapIp(ip)) return true;
        Instant now = Instant.now();
        return repo.findByEnvironmentOrderByIdAsc(settings.environment()).stream()
            .filter(e -> !e.getId().equals(excludedId))
            .filter(e -> e.isLive(now))
            .anyMatch(e -> Cidr.parse(e.getCidr()).contains(ip));
    }

    public List<IpAllowEntry> list() { return repo.findByEnvironmentOrderByIdAsc(settings.environment()); }

    public IpAllowEntry add(AdminContext ctx, String cidrText, String description, Instant expiresAt, String reason) {
        Cidr cidr = parse(cidrText);
        if (repo.existsByEnvironmentAndCidr(settings.environment(), cidr.canonical()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That address is already on the list.");
        if (expiresAt != null && expiresAt.isBefore(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The expiry is in the past.");
        IpAllowEntry e = new IpAllowEntry();
        e.setCidr(cidr.canonical()); e.setDescription(Redactor.clean(description)); e.setEnvironment(settings.environment());
        e.setCreatedBy(ctx.email()); e.setCreatedAt(Instant.now()); e.setExpiresAt(expiresAt);
        e = repo.save(e);
        audit.record(ctx, "IP_ALLOWLIST_ADDED", "IP_ALLOWLIST", String.valueOf(e.getId()), "CREATE", null, snapshot(e), reason);
        return e;
    }

    public IpAllowEntry update(AdminContext ctx, Long id, String cidrText, String description, Instant expiresAt,
                               EntryStatus status, String reason) {
        IpAllowEntry e = find(id);
        Map<String, Object> before = snapshot(e);
        Cidr cidr = cidrText == null ? Cidr.parse(e.getCidr()) : parse(cidrText);
        EntryStatus newStatus = status == null ? e.getStatus() : status;
        boolean removesAccess = newStatus == EntryStatus.DISABLED || !cidr.canonical().equals(e.getCidr())
            || (expiresAt != null && expiresAt.isBefore(Instant.now()));
        if (removesAccess) guardSelfLockout(ctx, e, cidr, newStatus, expiresAt);
        e.setCidr(cidr.canonical());
        if (description != null) e.setDescription(Redactor.clean(description));
        e.setExpiresAt(expiresAt); e.setStatus(newStatus);
        e.setUpdatedBy(ctx.email()); e.setUpdatedAt(Instant.now());
        e = repo.save(e);
        audit.record(ctx, "IP_ALLOWLIST_UPDATED", "IP_ALLOWLIST", String.valueOf(id), "UPDATE", before, snapshot(e), reason);
        return e;
    }

    public void delete(AdminContext ctx, Long id, String reason) {
        IpAllowEntry e = find(id);
        if (!isAllowedExcluding(ctx.ip(), id))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refused: removing this entry would block your own address. Add another entry for it first.");
        repo.delete(e);
        audit.record(ctx, "IP_ALLOWLIST_REMOVED", "IP_ALLOWLIST", String.valueOf(id), "DELETE", snapshot(e), null, reason);
    }

    /** Emergency: turn one entry off now. Never refuses for self-lockout reasons beyond the caller's own address. */
    public IpAllowEntry disable(AdminContext ctx, Long id, String reason) {
        IpAllowEntry e = find(id);
        Map<String, Object> before = snapshot(e);
        if (!isAllowedExcluding(ctx.ip(), id))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refused: this entry is the only thing allowing your own address.");
        e.setStatus(EntryStatus.DISABLED); e.setUpdatedBy(ctx.email()); e.setUpdatedAt(Instant.now());
        e = repo.save(e);
        audit.record(ctx, "IP_ALLOWLIST_EMERGENCY_DISABLED", "IP_ALLOWLIST", String.valueOf(id), "DISABLE", before, snapshot(e), reason);
        return e;
    }

    private void guardSelfLockout(AdminContext ctx, IpAllowEntry current, Cidr newCidr, EntryStatus newStatus, Instant newExpiry) {
        if (isAllowedExcluding(ctx.ip(), current.getId())) return;
        boolean stillCovered = newStatus == EntryStatus.ACTIVE && newCidr.contains(ctx.ip())
            && (newExpiry == null || newExpiry.isAfter(Instant.now()));
        if (!stillCovered)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refused: this change would block your own address.");
    }

    private Cidr parse(String text) {
        try { return Cidr.parse(text); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()); }
    }

    private IpAllowEntry find(Long id) {
        IpAllowEntry e = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such entry."));
        if (e.getEnvironment() != settings.environment()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such entry.");
        return e;
    }

    private static Map<String, Object> snapshot(IpAllowEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cidr", e.getCidr()); m.put("description", e.getDescription()); m.put("status", e.getStatus().name());
        m.put("expiresAt", e.getExpiresAt() == null ? null : e.getExpiresAt().toString());
        m.put("environment", e.getEnvironment() == null ? null : e.getEnvironment().name());
        return m;
    }

    AdminEnvironment env() { return settings.environment(); }
}
