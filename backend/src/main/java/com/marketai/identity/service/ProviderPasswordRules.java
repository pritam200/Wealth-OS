package com.marketai.identity.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which password formats each provider actually uses, in the order they should be tried.
 *
 * <p>Per-provider rather than one global rule, because the conventions genuinely differ and
 * assuming otherwise is how a correct PAN gets reported as a wrong password. CAMS and NSE use
 * PAN in uppercase; CDSL/NSDL eCAS uses the first four PAN characters plus date of birth; many
 * banks use DOB alone. KFintech is the notable exception — its CAS password is user-defined at
 * request time, so no identity-derived strategy can produce it and only a saved credential will
 * work.
 *
 * <p>Ordering matters and is not arbitrary: the list is shortest-path-first, so the single most
 * likely format for that provider is attempted before any compound one. A provider with no
 * entry falls back to {@link #DEFAULT_ORDER} rather than to an empty list, so an unrecognised
 * sender still gets the common conventions tried — but never more than the bounded set.
 */
public final class ProviderPasswordRules {

    /**
     * Applied to a provider with no explicit rule. Deliberately short: these three cover the
     * overwhelming majority of Indian statement conventions, and every additional entry is
     * another failed attempt against a provider that may lock out.
     */
    public static final List<PasswordStrategy> DEFAULT_ORDER = List.of(
        PasswordStrategy.PAN_UPPERCASE,
        PasswordStrategy.DOB_DDMMYYYY,
        PasswordStrategy.PAN_UPPERCASE_PLUS_DOB_DDMMYYYY);

    private static final Map<String, List<PasswordStrategy>> RULES = new LinkedHashMap<>();

    private static void rule(List<PasswordStrategy> strategies, String... domains) {
        for (String d : domains) RULES.put(d, strategies);
    }

    static {
        // Registrars and exchanges — PAN uppercase is the published convention.
        rule(List.of(PasswordStrategy.PAN_UPPERCASE,
                     PasswordStrategy.PAN_UPPERCASE_PLUS_DOB_DDMMYYYY),
             "camsonline.com", "cams.com", "nse.co.in", "nseindia.com", "bseindia.com");

        // Depositories — eCAS uses first-4-of-PAN + DOB.
        rule(List.of(PasswordStrategy.PAN_FIRST4_PLUS_DOB_DDMMYYYY,
                     PasswordStrategy.PAN_UPPERCASE,
                     PasswordStrategy.DOB_DDMMYYYY),
             "cdslindia.com", "nsdl.co.in", "nsdl.com");

        // KFintech CAS passwords are user-defined when the statement is requested, so nothing
        // derived from identity can open one. Only a saved credential will. Listing it with an
        // empty derived set stops the engine burning attempts on formats that cannot succeed.
        rule(List.of(), "kfintech.com", "karvy.com");

        // Brokers — PAN uppercase.
        rule(List.of(PasswordStrategy.PAN_UPPERCASE),
             "mstock.com", "zerodha.com", "upstox.com", "groww.in",
             "angelone.in", "angelbroking.com", "icicidirect.com", "hdfcsec.com",
             "kotaksecurities.com", "sbisecurities.in", "5paisa.com", "sharekhan.com",
             "axisdirect.in", "motilaloswal.com", "indiainfoline.com", "dhan.co");

        // AMCs — PAN uppercase, DOB as the secondary.
        rule(List.of(PasswordStrategy.PAN_UPPERCASE, PasswordStrategy.DOB_DDMMYYYY),
             "sbimf.com", "hdfcfund.com", "iciciprumf.com", "utimf.com",
             "nipponindiamf.com", "franklintempletonindia.com", "dspmf.com", "tatamf.com");

        // Banks — DOB first; card and account statements commonly use it.
        rule(List.of(PasswordStrategy.DOB_DDMMYYYY,
                     PasswordStrategy.PAN_UPPERCASE,
                     PasswordStrategy.DOB_DDMMYY),
             "hdfcbank.com", "hdfcbank.net", "icicibank.com", "axisbank.com",
             "sbi.co.in", "onlinesbi.com", "sbicard.com", "kotak.com",
             "idfcfirstbank.com", "yesbank.in");
    }

    private ProviderPasswordRules() {}

    /** Strategies for a provider domain, or {@link #DEFAULT_ORDER} when unknown. */
    public static List<PasswordStrategy> forProvider(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) return DEFAULT_ORDER;

        String key = providerKey.trim().toLowerCase();
        List<PasswordStrategy> exact = RULES.get(key);
        if (exact != null) return exact;

        // Subdomain match: statements.mstock.com inherits mstock.com's rule. The leading dot
        // stops "notmstock.com" from matching.
        for (Map.Entry<String, List<PasswordStrategy>> e : RULES.entrySet()) {
            if (key.endsWith("." + e.getKey())) return e.getValue();
        }
        return DEFAULT_ORDER;
    }

    /** True when the provider is known to use a user-defined password no rule can derive. */
    public static boolean isUserDefinedOnly(String providerKey) {
        return forProvider(providerKey).isEmpty();
    }

    public static boolean isKnown(String providerKey) {
        if (providerKey == null) return false;
        String key = providerKey.trim().toLowerCase();
        if (RULES.containsKey(key)) return true;
        return RULES.keySet().stream().anyMatch(d -> key.endsWith("." + d));
    }
}
