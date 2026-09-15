package com.marketai.document.classify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Detects sender impersonation by comparing what the display name claims against what the
 * envelope domain proves.
 *
 * <p>This exists because of how the legacy parser routing selects a parser. It asks
 * {@code containsIgnoreCase(from, "zerodha")} where {@code from} is the raw {@code From} header
 * — display name included, and the display name is chosen by the sender. So:
 *
 * <pre>
 *   "Zerodha Alerts" &lt;noreply@attacker.example&gt;
 *       matches containsIgnoreCase(from, "zerodha")
 *       routed to ZerodhaParser
 *       whatever it extracts heads toward the ledger
 * </pre>
 *
 * <p>The evaluation is deliberately narrow. It fires only when a display name <em>names an
 * issuer</em> that the envelope domain does not support — positive evidence of a claim that
 * cannot be verified. An unrecognised sender making no such claim is
 * {@link SenderTrust#UNKNOWN_DOMAIN} and is not blocked, because most of those are legitimate
 * issuers missing from the registry rather than attacks.
 */
@Component
@Slf4j
public class SenderTrustEvaluator {

    /**
     * Tokens that, appearing in a display name, amount to claiming to be a given issuer.
     *
     * <p>Sourced from the substrings the existing parsers actually match on, so the evaluator
     * covers exactly the claims the legacy routing would have acted upon — not a different,
     * invented set.
     */
    private static final Map<String, String> CLAIM_TOKENS = new LinkedHashMap<>();

    private static void claim(String issuer, String... tokens) {
        for (String t : tokens) CLAIM_TOKENS.put(t, issuer);
    }

    static {
        claim("Zerodha",      "zerodha", "kite");
        claim("Groww",        "groww");
        claim("Upstox",       "upstox");
        claim("Angel One",    "angelone", "angel broking", "angelbroking");
        claim("ICICI Direct", "icicidirect", "icici direct");
        claim("m.Stock",      "mstock", "m.stock", "miraeasset");
        claim("CAMS",         "camsonline", "cams");
        claim("KFintech",     "kfintech", "karvy");
        claim("CDSL",         "cdsl");
        claim("NSDL",         "nsdl");
        claim("HDFC Bank",    "hdfcbank", "hdfc bank");
        claim("ICICI Bank",   "icicibank", "icici bank");
        claim("Axis Bank",    "axisbank", "axis bank");
        claim("SBI",          "sbicard", "state bank");
        claim("Kotak",        "kotak");
        claim("IDFC First",   "idfcfirst", "idfc first");
        claim("Yes Bank",     "yesbank", "yes bank");
    }

    public record Assessment(SenderTrust trust, String claimedIssuer,
                             String verifiedIssuer, String detail) {

        public boolean permitsAutoImport() { return trust.permitsAutoImport(); }
    }

    public Assessment evaluate(String rawFromHeader) {
        String domain = ClassificationCandidate.email(rawFromHeader, null, null).senderDomain();
        if (domain == null) {
            return new Assessment(SenderTrust.UNPARSEABLE, null, null,
                "No parseable sender address in the From header");
        }

        String verifiedIssuer = IssuerDomainRegistry.issuerFor(domain).orElse(null);

        // The domain is excluded from the claim search ONLY when it is verified. For an
        // unverified domain the domain text is precisely where a lookalike hides
        // (zerodha.com.evil.example), so it must stay in scope — stripping it there would
        // downgrade a lookalike to a merely-unknown sender and let it import.
        String claimedIssuer = claimedIssuer(rawFromHeader, verifiedIssuer != null ? domain : null)
            .orElse(null);

        if (verifiedIssuer != null) {
            // The domain proves an issuer. A display name naming a *different* issuer is the
            // clearest impersonation signal there is.
            if (claimedIssuer != null && !claimedIssuer.equals(verifiedIssuer)) {
                return new Assessment(SenderTrust.IMPERSONATION_SUSPECTED, claimedIssuer, verifiedIssuer,
                    String.format("Display name claims %s but the sending domain %s belongs to %s",
                        claimedIssuer, domain, verifiedIssuer));
            }
            return new Assessment(SenderTrust.VERIFIED_DOMAIN, claimedIssuer, verifiedIssuer,
                "Sending domain " + domain + " belongs to " + verifiedIssuer);
        }

        if (claimedIssuer != null) {
            // Names an issuer from a domain that does not belong to it. This is the case the
            // legacy substring routing would have handed straight to that issuer's parser.
            return new Assessment(SenderTrust.IMPERSONATION_SUSPECTED, claimedIssuer, null,
                String.format("Display name claims %s but the sending domain %s is not one of its "
                    + "known domains", claimedIssuer, domain));
        }

        return new Assessment(SenderTrust.UNKNOWN_DOMAIN, null, null,
            "Sending domain " + domain + " is not in the issuer registry");
    }

    /**
     * Issuer named anywhere in the header, optionally excluding a verified domain.
     *
     * @param verifiedDomain when non-null, this domain is removed from the search so a genuine
     *        {@code noreply@zerodha.com} is not read as "claiming" to be Zerodha — that is what
     *        the domain already proves. Pass null for an unverified domain, so a lookalike
     *        domain still counts as a claim.
     */
    private Optional<String> claimedIssuer(String rawFrom, String verifiedDomain) {
        if (rawFrom == null) return Optional.empty();
        String haystack = rawFrom.toLowerCase(Locale.ROOT);

        if (verifiedDomain != null) {
            int idx = haystack.indexOf(verifiedDomain);
            if (idx >= 0) {
                haystack = haystack.substring(0, idx) + " "
                    + haystack.substring(idx + verifiedDomain.length());
            }
        }

        for (Map.Entry<String, String> e : CLAIM_TOKENS.entrySet()) {
            if (haystack.contains(e.getKey())) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }
}
