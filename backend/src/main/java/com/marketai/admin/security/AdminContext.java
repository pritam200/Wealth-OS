package com.marketai.admin.security;

import com.marketai.admin.domain.AdminEnvironment;
import com.marketai.admin.domain.AdminRole;

/** Who is calling the admin API, as established by the access filter. */
public record AdminContext(String requestId, String ip, String email, AdminRole role,
                           AdminEnvironment environment, boolean bootstrap) {
    public static final String ATTR = "marketai.admin.context";
}
