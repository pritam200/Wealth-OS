package com.marketai.admin.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Keeps secrets and log-forging characters out of audit and log text. */
public final class Redactor {
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(pass|secret|token|key|credential|authorization|otp|pin|private|cookie|signature).*");
    public static final String MASK = "********";

    private Redactor() {}

    public static Map<String, Object> redact(Map<String, ?> in) {
        if (in == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(clean(k), SECRET_KEY.matcher(k).matches() ? MASK : value(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object value(Object v) {
        if (v instanceof Map<?, ?> m) return redact((Map<String, ?>) m);
        if (v instanceof String s) return clean(s);
        return v;
    }

    /** Strips control characters (newlines would forge log lines) and caps the length. */
    public static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[\\p{Cntrl}&&[^ ]]", "?");
        return t.length() > 500 ? t.substring(0, 500) + "…" : t;
    }
}
