package com.marketai.document.classify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SenderTrustEvaluatorTest {

    private final SenderTrustEvaluator evaluator = new SenderTrustEvaluator();

    @Test
    @DisplayName("a genuine issuer address is verified and imports freely")
    void genuineSenderIsVerified() {
        var a = evaluator.evaluate("\"Zerodha\" <noreply@zerodha.com>");

        assertThat(a.trust()).isEqualTo(SenderTrust.VERIFIED_DOMAIN);
        assertThat(a.verifiedIssuer()).isEqualTo("Zerodha");
        assertThat(a.permitsAutoImport()).isTrue();
    }

    @Test
    @DisplayName("the issuer's own domain is not mistaken for a claim about itself")
    void domainIsNotReadAsAClaim() {
        // "zerodha" appears in the domain. Treating that as a display-name claim would make
        // every legitimate sender look like it was impersonating itself.
        var a = evaluator.evaluate("noreply@zerodha.com");

        assertThat(a.trust()).isEqualTo(SenderTrust.VERIFIED_DOMAIN);
        assertThat(a.permitsAutoImport()).isTrue();
    }

    @ParameterizedTest
    @DisplayName("a display name claiming an issuer it cannot prove is held back")
    @ValueSource(strings = {
        "\"Zerodha Alerts\" <noreply@attacker.example>",
        "\"CAMS Statement\" <mail@phish.example>",
        "\"HDFC Bank\" <alerts@evil.test>",
        "Kotak <no-reply@notkotak.example>"
    })
    void impersonationIsDetected(String from) {
        var a = evaluator.evaluate(from);

        assertThat(a.trust()).isEqualTo(SenderTrust.IMPERSONATION_SUSPECTED);
        assertThat(a.claimedIssuer()).isNotNull();
        assertThat(a.permitsAutoImport()).isFalse();
        assertThat(a.detail()).contains("claims");
    }

    @Test
    @DisplayName("one known issuer impersonating another is caught")
    void crossIssuerImpersonationIsCaught() {
        var a = evaluator.evaluate("\"Zerodha Support\" <noreply@groww.in>");

        assertThat(a.trust()).isEqualTo(SenderTrust.IMPERSONATION_SUSPECTED);
        assertThat(a.claimedIssuer()).isEqualTo("Zerodha");
        assertThat(a.verifiedIssuer()).isEqualTo("Groww");
    }

    @Test
    @DisplayName("an unregistered issuer is NOT blocked — that would drop real transactions")
    void unknownDomainsStillImport() {
        // The registry will always lag reality. If adding a bank meant losing its transactions
        // until someone noticed, the check would cost more than it saves.
        var a = evaluator.evaluate("statements@somenewbroker.in");

        assertThat(a.trust()).isEqualTo(SenderTrust.UNKNOWN_DOMAIN);
        assertThat(a.permitsAutoImport()).isTrue();
        assertThat(a.claimedIssuer()).isNull();
    }

    @Test
    void unparseableSendersAreRecordedButNotBlocked() {
        var a = evaluator.evaluate("not-an-address");

        assertThat(a.trust()).isEqualTo(SenderTrust.UNPARSEABLE);
        assertThat(a.permitsAutoImport()).isTrue();
        assertThat(a.detail()).contains("No parseable sender");
    }

    @ParameterizedTest
    @DisplayName("a genuine issuer domain variant missing from the registry is verified, not "
        + "flagged as impersonation — these are real senders a live audit found blocked")
    @ValueSource(strings = {
        "\"Kotak Securities\" <noreply@kotaksecurities.com>",
        "<statements@angelbroking.in>",
        "<cas@camsonline.co.in>",
        "\"HDFC Bank\" <alerts@hdfcbank.bank.in>"
    })
    void previouslyMissingIssuerDomainVariantsAreNowVerified(String from) {
        var a = evaluator.evaluate(from);

        assertThat(a.trust()).isEqualTo(SenderTrust.VERIFIED_DOMAIN);
        assertThat(a.permitsAutoImport()).isTrue();
    }

    @Test
    void subdomainsOfAKnownIssuerAreVerified() {
        assertThat(evaluator.evaluate("\"Zerodha\" <alerts@mail.zerodha.com>").trust())
            .isEqualTo(SenderTrust.VERIFIED_DOMAIN);
    }

    @Test
    @DisplayName("a lookalike domain naming the issuer is impersonation, not an unknown sender")
    void lookalikeDomainsAreImpersonationNotUnknown() {
        var a = evaluator.evaluate("alerts@zerodha.com.evil.example");

        // The domain is unknown, but it *names* Zerodha outside a verified domain — which is
        // the whole point of a lookalike. Treating this as merely "unknown" would let it import.
        assertThat(a.trust()).isEqualTo(SenderTrust.IMPERSONATION_SUSPECTED);
        assertThat(a.claimedIssuer()).isEqualTo("Zerodha");
    }
}
