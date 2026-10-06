package com.marketai.admin.security;

import com.marketai.admin.domain.AdminRole;

public enum Permission {
    VIEW(AdminRole.READ_ONLY),
    EDIT(AdminRole.DEVELOPER),
    MANAGE_ACCESS(AdminRole.ADMIN);

    private final AdminRole minimum;
    Permission(AdminRole minimum) { this.minimum = minimum; }
    public boolean grantedTo(AdminRole role) { return role != null && role.atLeast(minimum); }
}
