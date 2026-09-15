package com.marketai.document.identity;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls payment-rail references out of document text.
 *
 * <p><b>Extraction is label-driven, never pattern-only.</b> A bare twelve-digit number in an
 * email is not evidence of an RRN — it is just as likely to be an account number, a phone
 * number, an amount in paise, or a customer id. Matching bare digit runs would mint confident
 * false references, and a false reference is worse than none: tier-1 matching treats it as
 * proof, so a collision would merge two genuinely different transactions into one and silently
 * lose a financial record.
 *
 * <p>So a reference is only harvested when the document explicitly names it.
 */
@Component
public class ReferenceHarvester {

    /**
     * Label → reference type, most specific first. The label alternatives are joined into one
     * group and followed by optional punctuation, then the value.
     *
     * <p>Value shapes are deliberately permissive (6–30 alphanumerics) because UTR formats vary
     * by bank — 16 characters for NEFT, up to 22 for RTGS, with bank-specific prefixes. The
     * label is doing the identification work; the shape only bounds it.
     */
    private static final Map<Pattern, ExternalReference.ReferenceType> PATTERNS = new LinkedHashMap<>();

    private static void pattern(ExternalReference.ReferenceType type, String labelAlternatives) {
        PATTERNS.put(Pattern.compile(
            "(?i)\\b(" + labelAlternatives + ")\\b[\\s:.\\-#]*([A-Z0-9][A-Z0-9\\-]{5,29})"),
            type);
    }

    static {
        // Most specific labels first — "UPI Ref No" must win over a generic "Ref No".
        pattern(ExternalReference.ReferenceType.UTR,
            "UTR\\s*(?:No\\.?|Number|Ref\\.?)?|Unique\\s+Transaction\\s+Reference");
        pattern(ExternalReference.ReferenceType.RRN,
            "RRN|Retrieval\\s+Reference(?:\\s+Number)?");
        pattern(ExternalReference.ReferenceType.UPI_TXN_ID,
            "UPI\\s*(?:Ref(?:erence)?|Transaction|Txn)\\s*(?:No\\.?|Number|ID)?");
        pattern(ExternalReference.ReferenceType.IMPS_REF,
            "IMPS\\s*(?:Ref(?:erence)?|Transaction|Txn)?\\s*(?:No\\.?|Number|ID)?");
        pattern(ExternalReference.ReferenceType.CHEQUE,
            "Cheque\\s*(?:No\\.?|Number)|Chq\\s*No\\.?");
        // Generic issuer references last, and only ever treated as issuer-scoped.
        pattern(ExternalReference.ReferenceType.ISSUER_REF,
            "Order\\s*(?:No\\.?|ID)|Transaction\\s*(?:No\\.?|ID)|Txn\\s*(?:No\\.?|ID)"
                + "|Reference\\s*(?:No\\.?|Number|ID)|Ref\\s*No\\.?");
    }

    /**
     * All references found, in pattern-priority order and de-duplicated by (type, value).
     *
     * <p>Returns every match rather than the first: a bank transfer notification legitimately
     * carries both a UTR and an issuer reference, and keeping both means a later document
     * quoting either one still matches.
     */
    public List<ExternalReference> harvest(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<ExternalReference> found = new ArrayList<>();
        for (Map.Entry<Pattern, ExternalReference.ReferenceType> e : PATTERNS.entrySet()) {
            Matcher m = e.getKey().matcher(text);
            while (m.find()) {
                String label = m.group(1).trim();
                String value = normalise(m.group(2));
                if (value.length() < 6) continue;

                ExternalReference ref = new ExternalReference(e.getValue(), value, label);
                boolean duplicate = found.stream()
                    .anyMatch(f -> f.type() == ref.type() && f.value().equals(ref.value()));
                if (!duplicate) found.add(ref);
            }
        }
        return List.copyOf(found);
    }

    /**
     * The single strongest reference, if any — preferring a globally-unique rail reference over
     * an issuer-scoped one. This is what tier-1 identity matching keys on.
     */
    public java.util.Optional<ExternalReference> strongest(String text) {
        // Harvest once. The previous form called harvest() again inside the or() supplier, which
        // re-ran all six regex passes over the whole document body — doubling the cost on every
        // import for a result already in hand.
        List<ExternalReference> found = harvest(text);

        return found.stream()
            .filter(ExternalReference::isGloballyUnique)
            .findFirst()
            .or(() -> found.stream().findFirst());
    }

    /** Upper-cased with separators removed, so "hdfc-n5-2024" and "HDFCN52024" agree. */
    private static String normalise(String raw) {
        return raw == null ? "" : raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
}
