package com.marketai.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Second, independent authorisation check. Every endpoint calls this itself rather than trusting
 * that the filter was configured for its path.
 */
@Component
public class AdminAuthz {

    private final com.marketai.admin.mfa.MfaService mfa;

    public AdminAuthz(com.marketai.admin.mfa.MfaService mfa) { this.mfa = mfa; }

    /** Like {@link #require} and, where the deployment demands it, a recent authenticator code. */
    public AdminContext requireStrong(HttpServletRequest request, Permission permission) {
        AdminContext ctx = require(request, permission);
        mfa.requireFresh(ctx);
        return ctx;
    }

    public AdminContext require(HttpServletRequest request, Permission permission) {
        Object attr = request.getAttribute(AdminContext.ATTR);
        if (!(attr instanceof AdminContext ctx)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorised.");
        if (!permission.grantedTo(ctx.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role does not allow this action.");
        return ctx;
    }
}
