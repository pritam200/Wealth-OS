package com.marketai.document.route;

import com.marketai.document.classify.*;
import com.marketai.gmail.parser.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cutover safety test: routing must never narrow coverage against the real parser set.
 *
 * Runs both selection paths over every parser this application actually has — not stubs — for a
 * spread of realistic sender/subject pairs. The assertion that matters is the absence of
 * LEGACY_ONLY and DISAGREE verdicts: routing may find a parser the substring scan missed, but it
 * must never fail to find one the scan handles today, because that is a silently dropped
 * transaction.
 */
class ParserRoutingCoverageTest {

    /** Every parser, in the order Spring injects them (bean order = declaration order here). */
    private static List<EmailParser> allParsers() {
        return List.of(
            new ZerodhaParser(), new GrowwParser(), new UpstoxParser(), new AngelOneParser(),
            new IciciDirectParser(), new MstockParser(),
            new CamsParser(), new KfintechParser(),
            new HdfcBankParser(), new IciciBankParser(), new AxisBankParser(),
            new SbiBankParser(), new KotakParser(), new IdfcBankParser(), new YesBankParser(),
            new BankTransactionParser(), new CardBillParser(), new DividendParser(),
            new UpiAppParser());
    }

    private final DocumentClassifier classifier =
        new DocumentClassifier(List.of(new SenderDomainStage(), new SubjectPatternStage()));
    private final ParserRouter router = new ParserRouter();

    private record Sample(String from, String subject) {}

    /** Realistic traffic: genuine issuer domains across brokers, registrars, banks and cards. */
    private static List<Sample> corpus() {
        return List.of(
            new Sample("\"Zerodha\" <noreply@zerodha.com>", "Contract Note cum Tax Invoice"),
            new Sample("noreply@zerodha.com", "Your trade confirmation for 12-Sep-2026"),
            new Sample("alerts@mail.zerodha.com", "Contract note"),
            new Sample("no-reply@groww.in", "Order executed - RELIANCE"),
            new Sample("noreply@upstox.com", "Contract note for your trades"),
            new Sample("support@angelone.in", "Trade confirmation"),
            new Sample("noreply@icicidirect.com", "Contract Note"),
            new Sample("service@mstock.com", "Contract note - settlement"),
            new Sample("donotreply@camsonline.com", "SIP purchase confirmation"),
            new Sample("noreply@camsonline.com", "Consolidated Account Statement - CAMS"),
            new Sample("noreply@kfintech.com", "Redemption confirmation"),
            new Sample("alerts@hdfcbank.com", "Fixed Deposit booking confirmation"),
            new Sample("alerts@hdfcbank.com", "Your RD booking is confirmed"),
            new Sample("noreply@icicibank.com", "Transaction alert - debit"),
            new Sample("alerts@axisbank.com", "Transaction alert: amount debited"),
            new Sample("alerts@sbicard.com", "Credit card statement"),
            new Sample("noreply@kotak.com", "Fixed deposit advice"),
            new Sample("alerts@idfcfirstbank.com", "Transaction alert - credit"),
            new Sample("alerts@yesbank.in", "Transaction alert debit"),
            // Unregistered issuers — routing must fall back to the full legacy list
            new Sample("statements@somenewbroker.in", "Contract Note"),
            new Sample("noreply@sharekhan.com", "Dividend credited"),
            new Sample("noreply@linkintime.co.in", "Interim dividend payment"),
            new Sample("noreply@paytm.com", "Payment successful"),
            new Sample("noreply@phonepe.com", "You paid Rs 450"),
            // Non-financial noise
            new Sample("friend@example.com", "lunch tomorrow?"),
            new Sample("newsletter@news.example", "This week in markets")
        );
    }

    private String legacySelection(String from, String subject, List<EmailParser> parsers) {
        for (EmailParser p : parsers) {
            if (p.canParse(from, subject)) return p.getClass().getSimpleName();
        }
        return null;
    }

    private String routedSelection(String from, String subject, List<EmailParser> parsers) {
        DocumentClassification c = classifier
            .classify(ClassificationCandidate.email(from, subject, ""))
            .asOptional().orElse(null);
        for (EmailParser p : router.candidatesFor(c, parsers)) {
            if (p.canParse(from, subject)) return p.getClass().getSimpleName();
        }
        return null;
    }

    /**
     * Sampling test: does routing change the outcome on realistic mail?
     *
     * <p>Note which test in this class is actually load-bearing. Verified by injecting a
     * deliberate narrowing into {@link ParserRouter} (dropping the non-preferred tail): this
     * sampling test <em>passed</em>, and {@link #everyCandidateListRetainsEveryParser} caught
     * it. The reason is structural — producing a LEGACY_ONLY verdict needs a known issuer whose
     * matching parser is neither its own nor a generic, and the parsers gate on issuer tokens in
     * the From header, so a genuine issuer domain rarely reaches another issuer's parser.
     *
     * <p>So this test guards against outcome changes on real traffic, while the invariant test
     * guards the property. Keep both, and do not treat this one alone as sufficient evidence.
     */
    @Test
    @DisplayName("routing never loses a parser the legacy scan finds (sampled)")
    void routingNeverNarrowsCoverage() {
        List<EmailParser> parsers = allParsers();
        List<String> regressions = new ArrayList<>();

        for (Sample s : corpus()) {
            String legacy = legacySelection(s.from(), s.subject(), parsers);
            String routed = routedSelection(s.from(), s.subject(), parsers);
            SelectionComparison cmp = SelectionComparison.of(legacy, routed);

            if (cmp.isRegression()) {
                regressions.add(s.from() + " / \"" + s.subject() + "\" → " + cmp.detail());
            }
        }

        // A failure here is the signal not to cut over, and names the exact sample that broke.
        assertThat(regressions)
            .as("routing regressions — each would be a transaction that imports today and "
                + "would stop importing after cutover")
            .isEmpty();
    }

    @Test
    @DisplayName("routing puts the issuer's own parser first when the domain identifies one")
    void issuerParserIsPreferred() {
        List<EmailParser> parsers = allParsers();
        DocumentClassification zerodha = classifier
            .classify(ClassificationCandidate.email("noreply@zerodha.com", "Contract Note", ""))
            .asOptional().orElseThrow();

        List<EmailParser> ordered = router.candidatesFor(zerodha, parsers);

        assertThat(ordered.getFirst().getClass().getSimpleName()).isEqualTo("ZerodhaParser");
        assertThat(router.preferredParserName(zerodha)).isEqualTo("ZerodhaParser");
    }

    @Test
    @DisplayName("an unknown issuer gets the legacy order back, unchanged and complete")
    void unknownIssuerFallsBackToTheFullList() {
        List<EmailParser> parsers = allParsers();
        DocumentClassification unknown = classifier
            .classify(ClassificationCandidate.email("x@somenewbroker.in", "Contract Note", ""))
            .asOptional().orElse(null);

        List<EmailParser> ordered = router.candidatesFor(unknown, parsers);

        // Same members, same order. Narrowing here is exactly how a routing change silently
        // stops importing a real sender.
        assertThat(ordered).containsExactlyElementsOf(parsers);
    }

    /**
     * The invariant, and the test that actually catches narrowing: routing reorders the
     * candidate list, it never removes from it. Proven to go red against a deliberately
     * narrowed router — see the note on {@link #routingNeverNarrowsCoverage}.
     */
    @Test
    @DisplayName("routing reorders the candidates and never removes any")
    void everyCandidateListRetainsEveryParser() {
        List<EmailParser> parsers = allParsers();
        for (Sample s : corpus()) {
            DocumentClassification c = classifier
                .classify(ClassificationCandidate.email(s.from(), s.subject(), ""))
                .asOptional().orElse(null);

            assertThat(router.candidatesFor(c, parsers))
                .as("candidates for %s", s.from())
                .containsExactlyInAnyOrderElementsOf(parsers);
        }
    }

    @Test
    @DisplayName("registry issuers without a parser are known, not a surprise")
    void unmappedIssuersAreEnumerable() {
        var unmapped = ExtractorRegistry.unmappedIssuers(
            java.util.Set.of("Zerodha", "Dhan", "CDSL", "NSDL", "MF Central", "HDFC Bank"));

        // These have recognised domains for sender-trust purposes but no dedicated parser yet.
        // Routing falls through to the generics for them, which is correct and intentional.
        assertThat(unmapped).containsExactlyInAnyOrder("Dhan", "CDSL", "NSDL", "MF Central");
    }
}
