package com.marketai.admin.config;

import com.marketai.admin.domain.AdminFeatureFlag;
import com.marketai.admin.domain.AdminSecretMeta;
import com.marketai.admin.repo.AdminFeatureFlagRepository;
import com.marketai.admin.repo.AdminSecretMetaRepository;
import com.marketai.admin.security.AdminContext;
import com.marketai.admin.service.AdminAuditService;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
public class ConfigConsoleService {

    private final ConfigRegistry registry;
    private final Environment env;
    private final SecretStore store;
    private final AdminSecretMetaRepository metaRepo;
    private final AdminFeatureFlagRepository flagRepo;
    private final AdminAuditService audit;

    public ConfigConsoleService(ConfigRegistry registry, Environment env, SecretStore store, AdminSecretMetaRepository metaRepo,
                                AdminFeatureFlagRepository flagRepo, AdminAuditService audit) {
        this.registry = registry; this.env = env; this.store = store; this.metaRepo = metaRepo; this.flagRepo = flagRepo; this.audit = audit;
    }

    /** Non-secret values are shown; for a secret only whether it is set, when it was rotated, and a masked tail. */
    public List<Map<String, Object>> view() {
        Map<String, AdminSecretMeta> meta = new HashMap<>();
        metaRepo.findAll().forEach(m -> meta.put(m.getName(), m));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConfigItem i : registry.items()) {
            String v = env.getProperty(i.name());
            boolean set = v != null && !v.isBlank();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", i.name()); m.put("category", i.category()); m.put("description", i.description());
            m.put("secret", i.secret()); m.put("configured", set);
            if (i.secret()) {
                m.put("display", !set ? "Not configured" : "********" + (v.length() >= 20 ? v.substring(v.length() - 4) : ""));
                AdminSecretMeta sm = meta.get(i.name());
                m.put("lastRotatedAt", sm == null ? null : sm.getLastRotatedAt());
                m.put("rotatedBy", sm == null ? null : sm.getRotatedBy());
            } else {
                m.put("display", !set ? "Not set" : (v.length() > 200 ? v.substring(0, 200) + "…" : v));
            }
            out.add(m);
        }
        return out;
    }

    public boolean rotationAvailable() { return store.available(); }

    /** The value goes to the secret manager only. It is not stored, logged or audited; the audit names the secret. */
    public void rotate(AdminContext ctx, String name, String value, String reason) {
        ConfigItem item = registry.find(name).filter(ConfigItem::secret)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That is not a rotatable secret."));
        if (reason == null || reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required.");
        if (value == null || value.length() < 8 || value.length() > 4096 || value.matches(".*[\\p{Cntrl}].*"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The new value must be 8 to 4096 characters with no control characters.");
        store.put(item.name(), value);
        AdminSecretMeta m = metaRepo.findById(name).orElseGet(AdminSecretMeta::new);
        m.setName(name); m.setLastRotatedAt(Instant.now()); m.setRotatedBy(ctx.email());
        metaRepo.save(m);
        audit.record(ctx, "SECRET_ROTATED", "SECRET", name, "ROTATE", null, Map.of("restartRequired", true), reason);
    }

    public List<Map<String, Object>> flags() {
        Map<String, AdminFeatureFlag> saved = new HashMap<>();
        flagRepo.findAll().forEach(f -> saved.put(f.getName(), f));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String n : registry.flags()) {
            AdminFeatureFlag f = saved.get(n);
            out.add(Map.of("name", n, "enabled", f != null && f.isEnabled(), "updatedBy", f == null || f.getUpdatedBy() == null ? "" : f.getUpdatedBy()));
        }
        return out;
    }

    public boolean flag(String name) { return flagRepo.findById(name).map(AdminFeatureFlag::isEnabled).orElse(false); }

    public void setFlag(AdminContext ctx, String name, boolean enabled, String reason) {
        if (!registry.flags().contains(name)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown feature flag.");
        if (reason == null || reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required.");
        AdminFeatureFlag f = flagRepo.findById(name).orElseGet(AdminFeatureFlag::new);
        boolean before = f.isEnabled();
        f.setName(name); f.setEnabled(enabled); f.setUpdatedBy(ctx.email()); f.setUpdatedAt(Instant.now());
        flagRepo.save(f);
        audit.record(ctx, "FEATURE_FLAG_CHANGED", "FLAG", name, "UPDATE", Map.of("enabled", before), Map.of("enabled", enabled), reason);
    }
}
