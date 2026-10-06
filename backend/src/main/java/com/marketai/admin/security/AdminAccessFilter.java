package com.marketai.admin.security;

import com.marketai.admin.domain.AdminRequestLog;
import com.marketai.admin.domain.AdminRole;
import com.marketai.admin.net.ClientIpResolver;
import com.marketai.admin.service.EmailAllowlistService;
import com.marketai.admin.service.IpAllowlistService;
import com.marketai.admin.service.LockdownService;
import com.marketai.admin.service.Redactor;
import com.marketai.admin.service.RequestLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The admin pipeline: client IP → rate limit → lockdown → IP allowlist → authentication → email
 * allowlist/role → production confirmation → (controller authorises each endpoint again) → request
 * log. Every refusal is fail-closed and the response never says which check failed; the reason
 * goes to the request log only.
 *
 * Registered only inside the Spring Security chain, after JwtAuthFilter, so the JWT is already read.
 */
@Component
@Slf4j
public class AdminAccessFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/api/admin";
    public static final String CONFIRM_HEADER = "X-Confirm-Environment";
    static final String RELEASE_PATH = PREFIX + "/security/lockdown/release";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ClientIpResolver ipResolver;
    private final IpAllowlistService ips;
    private final EmailAllowlistService emails;
    private final LockdownService lockdown;
    private final RequestLogService requestLog;
    private final AdminSettings settings;
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    public AdminAccessFilter(ClientIpResolver ipResolver, IpAllowlistService ips, EmailAllowlistService emails,
                             LockdownService lockdown, RequestLogService requestLog, AdminSettings settings) {
        this.ipResolver = ipResolver; this.ips = ips; this.emails = emails;
        this.lockdown = lockdown; this.requestLog = requestLog; this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        boolean admin = uri.equals(PREFIX) || uri.startsWith(PREFIX + "/");
        return !admin || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        res.setHeader("X-Request-Id", requestId);
        res.setHeader("Cache-Control", "no-store");
        String ip = ipResolver.resolve(req).orElse(null);
        String email = null;
        String denyReason = null;
        int denyStatus = 0;
        try {
            if (ip == null) { denyStatus = 403; denyReason = "CLIENT_IP_UNKNOWN"; }
            else if (!allowRate(ip)) { denyStatus = 429; denyReason = "RATE_LIMITED"; }

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            boolean authenticated = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
            if (authenticated) email = auth.getName().toLowerCase(Locale.ROOT);

            boolean bootstrapAdmin = email != null && settings.isBootstrapEmail(email);
            boolean releasing = bootstrapAdmin && settings.isBootstrapIp(ip) && RELEASE_PATH.equals(req.getRequestURI());

            if (denyReason == null && lockdown.isEngaged() && !releasing) { denyStatus = 503; denyReason = "LOCKDOWN"; }
            if (denyReason == null && !ips.isAllowed(ip)) { denyStatus = 403; denyReason = "IP_NOT_ALLOWED"; }
            if (denyReason == null && !authenticated) { denyStatus = 401; denyReason = "NOT_AUTHENTICATED"; }

            Optional<AdminRole> role = Optional.empty();
            if (denyReason == null) {
                role = emails.roleFor(email);
                if (role.isEmpty()) { denyStatus = 403; denyReason = "EMAIL_NOT_ALLOWED"; }
            }
            if (denyReason == null && !SAFE_METHODS.contains(req.getMethod())) {
                String confirm = req.getHeader(CONFIRM_HEADER);
                if (settings.environment().name().equals("PRODUCTION") && !"PRODUCTION".equals(confirm)) {
                    denyStatus = 412; denyReason = "ENVIRONMENT_NOT_CONFIRMED";
                } else if (confirm != null && !confirm.equalsIgnoreCase(settings.environment().name())) {
                    denyStatus = 412; denyReason = "ENVIRONMENT_MISMATCH";
                }
            }

            if (denyReason != null) {
                log.warn("Admin request denied: {} requestId={} ip={}", denyReason, requestId, Redactor.clean(ip));
                deny(res, denyStatus, requestId, denyReason);
                return;
            }
            req.setAttribute(AdminContext.ATTR, new AdminContext(requestId, ip, email, role.get(), settings.environment(), bootstrapAdmin));
            chain.doFilter(req, res);
        } finally {
            writeLog(req, res, requestId, ip, email, denyReason, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private void deny(HttpServletResponse res, int status, String requestId, String reason) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        String message = switch (status) {
            case 401 -> "Authentication required.";
            case 429 -> "Too many requests.";
            case 503 -> "The admin console is temporarily unavailable.";
            case 412 -> "Environment confirmation is required for this change.";   // not a secret: the caller is already authenticated
            default -> "Access denied.";
        };
        res.getWriter().write("{\"error\":\"" + message + "\",\"requestId\":\"" + requestId + "\"}");
    }

    private void writeLog(HttpServletRequest req, HttpServletResponse res, String requestId, String ip, String email,
                          String denyReason, long durationMs) {
        AdminRequestLog row = new AdminRequestLog();
        row.setOccurredAt(Instant.now()); row.setRequestId(requestId); row.setSourceIp(Redactor.clean(ip));
        row.setActorEmail(Redactor.clean(email)); row.setMethod(Redactor.clean(req.getMethod()));
        String path = Redactor.clean(req.getRequestURI());
        row.setPath(path != null && path.length() > 300 ? path.substring(0, 300) : path);   // getRequestURI has no query string
        row.setStatus(res.getStatus()); row.setDurationMs(durationMs); row.setEnvironment(settings.environment().name());
        row.setDecision(denyReason == null ? "ALLOW" : "DENY"); row.setDenyReason(denyReason);
        String ua = Redactor.clean(req.getHeader("User-Agent"));
        row.setUserAgent(ua != null && ua.length() > 200 ? ua.substring(0, 200) : ua);
        requestLog.save(row);
    }

    /** Fixed one-minute window per source address; deliberately simple, in-process. */
    private boolean allowRate(String ip) {
        long minute = System.currentTimeMillis() / 60_000;
        if (windows.size() > 10_000) windows.entrySet().removeIf(e -> e.getValue()[0] != minute);
        long[] w = windows.compute(ip, (k, cur) -> {
            if (cur == null || cur[0] != minute) return new long[]{minute, 1};
            cur[1]++; return cur;
        });
        return w[1] <= settings.rateLimitPerMinute();
    }
}
