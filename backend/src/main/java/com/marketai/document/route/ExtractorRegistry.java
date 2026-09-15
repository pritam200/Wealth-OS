package com.marketai.document.route;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which parser handles which issuer.
 *
 * <p>Deliberately an explicit table rather than a naming convention. Inferring
 * {@code "HDFC Bank" -> HdfcBankParser} from the string would appear to work and then fail
 * silently on the cases that do not follow the pattern — {@code "IDFC First" -> IdfcBankParser},
 * {@code "SBI" -> SbiBankParser}, {@code "m.Stock" -> MstockParser}. A table that is wrong is
 * visibly wrong.
 *
 * <p>The issuer names are exactly those produced by
 * {@code IssuerDomainRegistry}, so the two tables are joinable. A mismatch between them is a
 * routing hole, which is what {@link #unmappedIssuers} exists to surface.
 */
public final class ExtractorRegistry {

    /** Issuer (as IssuerDomainRegistry names it) → parser simple class name. */
    private static final Map<String, String> ISSUER_PARSER = new LinkedHashMap<>();

    /**
     * Parsers that are not issuer-specific and stay in the running regardless of who sent the
     * document. A bank transaction alert, a card bill, a dividend advice and a UPI receipt all
     * arrive from many different senders, so pinning them to an issuer would lose coverage.
     */
    private static final Set<String> GENERIC_PARSERS = Set.of(
        "BankTransactionParser",
        "CardBillParser",
        "DividendParser",
        "UpiAppParser"
    );

    static {
        // Brokers
        ISSUER_PARSER.put("Zerodha",       "ZerodhaParser");
        ISSUER_PARSER.put("Groww",         "GrowwParser");
        ISSUER_PARSER.put("Upstox",        "UpstoxParser");
        ISSUER_PARSER.put("Angel One",     "AngelOneParser");
        ISSUER_PARSER.put("ICICI Direct",  "IciciDirectParser");
        ISSUER_PARSER.put("m.Stock",       "MstockParser");

        // MF registrars
        ISSUER_PARSER.put("CAMS",          "CamsParser");
        ISSUER_PARSER.put("KFintech",      "KfintechParser");

        // Banks
        ISSUER_PARSER.put("HDFC Bank",     "HdfcBankParser");
        ISSUER_PARSER.put("ICICI Bank",    "IciciBankParser");
        ISSUER_PARSER.put("Axis Bank",     "AxisBankParser");
        ISSUER_PARSER.put("SBI",           "SbiBankParser");
        ISSUER_PARSER.put("Kotak",         "KotakParser");
        ISSUER_PARSER.put("IDFC First",    "IdfcBankParser");
        ISSUER_PARSER.put("Yes Bank",      "YesBankParser");

        // Deliberately unmapped: Dhan, MF Central, CDSL, NSDL. Their domains are recognised for
        // sender-trust purposes but no dedicated parser exists yet, so routing must fall through
        // to the generic parsers rather than pretend a specialist handler is available.
    }

    private ExtractorRegistry() {}

    public static Optional<String> parserFor(String issuer) {
        return issuer == null ? Optional.empty()
            : Optional.ofNullable(ISSUER_PARSER.get(issuer));
    }

    public static boolean isGeneric(String parserSimpleName) {
        return GENERIC_PARSERS.contains(parserSimpleName);
    }

    /**
     * Issuers recognised by the domain registry that have no dedicated parser. Not an error —
     * see the static block — but worth being able to enumerate rather than discover in
     * production.
     */
    public static Set<String> unmappedIssuers(Set<String> knownIssuers) {
        return knownIssuers.stream()
            .filter(i -> !ISSUER_PARSER.containsKey(i))
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    static Map<String, String> table() { return Map.copyOf(ISSUER_PARSER); }
}
