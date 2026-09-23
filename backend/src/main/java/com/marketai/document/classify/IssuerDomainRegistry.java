package com.marketai.document.classify;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Known issuer domains.
 *
 * <p>Matching is on the <em>registrable domain of the envelope address</em>, not a substring of
 * the From header. That distinction is the security value of this stage. The legacy parser
 * routing asks {@code containsIgnoreCase(from, "zerodha")} against the raw header, so a message
 * whose display name is {@code "Zerodha Alerts"} but whose address is attacker-controlled is
 * handed to the Zerodha parser and whatever it extracts flows toward the ledger.
 *
 * <p>An exact-or-subdomain match also refuses lookalikes: {@code notzerodha.com} and
 * {@code zerodha.com.evil.example} both fail against {@code zerodha.com}, where a substring
 * check passes both.
 *
 * <p>This registry is intentionally conservative — unknown domains abstain rather than guess,
 * and the later cascade stages still get their turn.
 */
public final class IssuerDomainRegistry {

    /** Registrable domain → canonical issuer name. Insertion order is not significant. */
    private static final Map<String, String> DOMAINS = new LinkedHashMap<>();

    private static void put(String issuer, String... domains) {
        for (String d : domains) DOMAINS.put(d, issuer);
    }

    static {
        // Brokers
        put("Zerodha",       "zerodha.com", "zerodha.net", "kite.trade");
        put("Groww",         "groww.in");
        put("Upstox",        "upstox.com");
        // Angel One trades under both its current brand domain and its legacy
        // Angel Broking domains (.com AND .in — a real 2026 sender audit found live mail
        // from angelbroking.in misclassified as impersonation because only .com was listed).
        put("Angel One",     "angelone.in", "angelbroking.com", "angelbroking.in");
        put("ICICI Direct",  "icicidirect.com");
        put("m.Stock",       "mstock.com");
        put("Dhan",          "dhan.co");

        // Registrars / MF infrastructure
        // camsonline.co.in is CAMS's own .co.in mail domain, distinct from camsonline.com —
        // both are genuine, and only listing one caused real CAMS mail to be flagged.
        put("CAMS",          "camsonline.com", "cams.com", "camsonline.co.in");
        put("KFintech",      "kfintech.com", "karvy.com");
        put("MF Central",    "mfcentral.com");

        // Depositories
        put("CDSL",          "cdslindia.com");
        put("NSDL",          "nsdl.co.in", "nsdl.com");

        // Banks
        // hdfcbank.bank.in is HDFC's domain under RBI's .bank.in TLD — a genuine issuer domain,
        // not a lookalike; it was previously missing and caused real HDFC mail to be blocked.
        put("HDFC Bank",     "hdfcbank.com", "hdfcbank.net", "hdfcbank.bank.in");
        put("ICICI Bank",    "icicibank.com");
        put("Axis Bank",     "axisbank.com");
        put("SBI",           "sbi.co.in", "onlinesbi.com", "sbicard.com");
        // kotaksecurities.com is Kotak's brokerage arm — already trusted by
        // ProviderPasswordRules for password derivation, but previously absent here, so the
        // system would derive a password for a domain it simultaneously flagged as an attacker.
        put("Kotak",         "kotak.com", "kotaksecurities.com");
        put("IDFC First",    "idfcfirstbank.com");
        put("Yes Bank",      "yesbank.in");
    }

    private IssuerDomainRegistry() {}

    /**
     * Resolves an issuer for a sender domain by exact or subdomain match.
     *
     * @param senderDomain lower-cased domain from {@link ClassificationCandidate#senderDomain()}
     */
    public static Optional<String> issuerFor(String senderDomain) {
        if (senderDomain == null || senderDomain.isBlank()) return Optional.empty();
        String d = senderDomain.toLowerCase();

        String exact = DOMAINS.get(d);
        if (exact != null) return Optional.of(exact);

        // Subdomain: mail.zerodha.com matches zerodha.com. The leading dot is what stops
        // notzerodha.com from matching.
        for (Map.Entry<String, String> e : DOMAINS.entrySet()) {
            if (d.endsWith("." + e.getKey())) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    public static boolean isKnown(String senderDomain) {
        return issuerFor(senderDomain).isPresent();
    }

    static int size() { return DOMAINS.size(); }
}
