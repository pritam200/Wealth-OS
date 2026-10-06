package com.marketai.admin.service;

import com.marketai.admin.repo.AdminRequestLogRepository;
import com.marketai.admin.security.AdminSettings;
import com.marketai.auth.repository.RefreshTokenRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Read-only dashboards: traffic, sessions and what is deployed. */
@Service
public class AdminOverviewService {

    private final AdminRequestLogRepository logs;
    private final IpAllowlistService ips;
    private final EmailAllowlistService emails;
    private final LockdownService lockdown;
    private final AdminSettings settings;
    private final RefreshTokenRepository tokens;
    private final ObjectProvider<BuildProperties> build;
    private final ObjectProvider<GitProperties> git;
    private final org.springframework.core.env.Environment env;

    public AdminOverviewService(AdminRequestLogRepository logs, IpAllowlistService ips, EmailAllowlistService emails, LockdownService lockdown,
                                AdminSettings settings, RefreshTokenRepository tokens, ObjectProvider<BuildProperties> build,
                                ObjectProvider<GitProperties> git, org.springframework.core.env.Environment env) {
        this.logs = logs; this.ips = ips; this.emails = emails; this.lockdown = lockdown; this.settings = settings;
        this.tokens = tokens; this.build = build; this.git = git; this.env = env;
    }

    public Map<String, Object> dashboard() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("environment", settings.environment());
        m.put("requests24h", logs.countByOccurredAtAfter(since));
        m.put("denied24h", logs.countByOccurredAtAfterAndDecision(since, "DENY"));
        m.put("ipEntries", ips.list().size());
        m.put("adminEntries", emails.list().size());
        m.put("lockdown", lockdown.isEngaged());
        m.put("mfaRequired", settings.mfaRequired());
        return m;
    }

    public List<Map<String, Object>> sessions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : tokens.activeSessionCounts()) out.add(Map.of("email", row[0], "activeSessions", row[1]));
        return out;
    }

    public Map<String, Object> deployment() {
        Map<String, Object> m = new LinkedHashMap<>();
        BuildProperties b = build.getIfAvailable(); GitProperties g = git.getIfAvailable();
        m.put("environment", settings.environment());
        m.put("version", b == null ? "unknown" : b.getVersion());
        m.put("builtAt", b == null ? null : b.getTime());
        m.put("gitCommit", g == null ? "unknown" : g.getShortCommitId());
        m.put("startedAt", Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()));
        m.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        m.put("java", System.getProperty("java.version"));
        m.put("profiles", Arrays.asList(env.getActiveProfiles()));
        return m;
    }
}
