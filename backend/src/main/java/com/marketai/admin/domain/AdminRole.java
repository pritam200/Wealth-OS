package com.marketai.admin.domain;

/** Ordered by privilege: a higher level includes everything below it. */
public enum AdminRole {
    READ_ONLY(1), DEVELOPER(2), ADMIN(3);

    private final int level;
    AdminRole(int level) { this.level = level; }
    public boolean atLeast(AdminRole other) { return level >= other.level; }
}
