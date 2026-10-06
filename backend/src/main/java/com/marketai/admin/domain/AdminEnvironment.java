package com.marketai.admin.domain;

import java.util.Locale;

public enum AdminEnvironment {
    DEV, STAGE, PRODUCTION;

    /** Unknown or blank values fall back to PRODUCTION: the strictest mode is the safe mistake. */
    public static AdminEnvironment parse(String s) {
        if (s == null || s.isBlank()) return DEV;
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return PRODUCTION; }
    }
}
