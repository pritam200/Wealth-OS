package com.marketai.document.classify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reason the sender-domain stage exists.
 *
 * The legacy parser routing asks {@code containsIgnoreCase(from, "zerodha")} against the raw
 * From header — which includes the display name, and the display name is set by whoever sent
 * the message. So a mail from an arbitrary address with the display name "Zerodha Alerts" is
 * handed to ZerodhaParser, and whatever that parser extracts heads toward the ledger.
 *
 * These tests pin the stronger behaviour: authority comes from the envelope domain only.
 */
class SenderDomainSpoofingTest {

    private final SenderDomainStage stage = new SenderDomainStage();

    private String issuerOf(String from) {
        return stage.classify(ClassificationCandidate.email(from, "Contract Note", ""))
            .map(DocumentClassification::issuer)
            .orElse(null);
    }

    @Test
    @DisplayName("a genuine sender resolves to its issuer")
    void genuineSenderResolves() {
        assertThat(issuerOf("noreply@zerodha.com")).isEqualTo("Zerodha");
        assertThat(issuerOf("\"Zerodha\" <noreply@zerodha.com>")).isEqualTo("Zerodha");
        assertThat(issuerOf("alerts@mail.zerodha.com")).isEqualTo("Zerodha");
    }

    @ParameterizedTest
    @DisplayName("a spoofed display name does NOT resolve to the impersonated issuer")
    @ValueSource(strings = {
        "\"Zerodha Alerts\" <noreply@evil.example>",
        "\"Zerodha Support\" <billing@attacker.test>",
        "zerodha <no-reply@phish.example>",
        "\"CAMS Statement\" <mail@notcams.example>"
    })
    void displayNameIsNotAuthority(String from) {
        // A substring check on the raw header passes every one of these.
        assertThat(from.toLowerCase()).containsAnyOf("zerodha", "cams");
        assertThat(issuerOf(from)).isNull();
    }

    @ParameterizedTest
    @DisplayName("lookalike domains do not match")
    @ValueSource(strings = {
        "a@notzerodha.com",           // substring match would pass
        "a@zerodha.com.evil.example", // prefix match would pass
        "a@zerodhaa.com",
        "a@my-zerodha.com"
    })
    void lookalikeDomainsAreRejected(String from) {
        assertThat(issuerOf(from)).isNull();
    }

    @Test
    void malformedHeadersAbstainRatherThanGuess() {
        assertThat(issuerOf(null)).isNull();
        assertThat(issuerOf("")).isNull();
        assertThat(issuerOf("not-an-address")).isNull();
        assertThat(issuerOf("missing-domain@")).isNull();
        assertThat(issuerOf("no-dot@localhost")).isNull();
    }

    @Test
    @DisplayName("the bracketed address wins when the display name also looks like an address")
    void bracketedAddressIsAuthoritative() {
        // Display names can contain an @ to look legitimate in a client that truncates.
        assertThat(issuerOf("\"noreply@zerodha.com\" <attacker@evil.example>")).isNull();
        assertThat(issuerOf("\"billing@evil.example\" <noreply@zerodha.com>")).isEqualTo("Zerodha");
    }

    @Test
    void unknownButWellFormedSendersAbstain() {
        assertThat(issuerOf("statements@somenewbroker.in")).isNull();
    }
}
