package com.marketai.identity.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The password engine that closes the gap found in the audit: the system could read
 * "your password is your PAN in uppercase" out of an email and still had no PAN to use, so a
 * first-time user could never auto-unlock anything.
 */
class PasswordDerivationTest {

    private static final String PAN = "ABCDE1234F";
    private static final LocalDate DOB = LocalDate.of(1990, 3, 7);

    // --- Strategy derivation ---

    @Test
    @DisplayName("each strategy produces the format its provider actually publishes")
    void strategiesDeriveCorrectFormats() {
        assertThat(PasswordStrategy.PAN_UPPERCASE.derive(PAN, DOB)).isEqualTo("ABCDE1234F");
        assertThat(PasswordStrategy.PAN_LOWERCASE.derive(PAN, DOB)).isEqualTo("abcde1234f");
        assertThat(PasswordStrategy.DOB_DDMMYYYY.derive(PAN, DOB)).isEqualTo("07031990");
        assertThat(PasswordStrategy.DOB_DDMMYY.derive(PAN, DOB)).isEqualTo("070390");
        assertThat(PasswordStrategy.PAN_UPPERCASE_PLUS_DOB_DDMMYYYY.derive(PAN, DOB))
            .isEqualTo("ABCDE1234F07031990");
        assertThat(PasswordStrategy.PAN_FIRST4_PLUS_DOB_DDMMYYYY.derive(PAN, DOB))
            .isEqualTo("ABCD07031990");
        assertThat(PasswordStrategy.PAN_FIRST5_PLUS_DOB_DDMMYYYY.derive(PAN, DOB))
            .isEqualTo("ABCDE07031990");
    }

    @Test
    @DisplayName("single-digit days and months are zero-padded")
    void datesAreZeroPadded() {
        // 7 March must be 07031990, never 731990 — an unpadded date is simply a wrong password.
        assertThat(PasswordStrategy.DOB_DDMMYYYY.derive(null, LocalDate.of(1990, 3, 7)))
            .isEqualTo("07031990");
    }

    @Test
    @DisplayName("a strategy missing its inputs yields null rather than a malformed attempt")
    void missingInputsProduceNoCandidate() {
        // Returning a partial string would burn one of the bounded retries on a password that
        // cannot possibly be right.
        assertThat(PasswordStrategy.PAN_UPPERCASE.derive(null, DOB)).isNull();
        assertThat(PasswordStrategy.DOB_DDMMYYYY.derive(PAN, null)).isNull();
        assertThat(PasswordStrategy.PAN_UPPERCASE_PLUS_DOB_DDMMYYYY.derive(PAN, null)).isNull();
        assertThat(PasswordStrategy.PAN_FIRST4_PLUS_DOB_DDMMYYYY.derive("ABC", DOB)).isNull();
        assertThat(PasswordStrategy.CUSTOM_SAVED.derive(PAN, DOB)).isNull();
    }

    // --- Provider rules ---

    @Test
    @DisplayName("providers get their own published convention, not one global rule")
    void providerRulesDiffer() {
        assertThat(ProviderPasswordRules.forProvider("camsonline.com"))
            .startsWith(PasswordStrategy.PAN_UPPERCASE);
        // Depository eCAS uses first-4-of-PAN + DOB, which PAN-uppercase would never open.
        assertThat(ProviderPasswordRules.forProvider("cdslindia.com"))
            .startsWith(PasswordStrategy.PAN_FIRST4_PLUS_DOB_DDMMYYYY);
        // Banks lead with date of birth.
        assertThat(ProviderPasswordRules.forProvider("hdfcbank.com"))
            .startsWith(PasswordStrategy.DOB_DDMMYYYY);
    }

    @Test
    @DisplayName("KFintech is known to be underivable — no attempts are wasted on it")
    void userDefinedProvidersAreRecognised() {
        // Its CAS password is chosen by the user at request time, so no identity-derived format
        // can open it. Attempting the usual three would be three guaranteed failures.
        assertThat(ProviderPasswordRules.isUserDefinedOnly("kfintech.com")).isTrue();
        assertThat(ProviderPasswordRules.forProvider("kfintech.com")).isEmpty();
        assertThat(ProviderPasswordRules.isUserDefinedOnly("camsonline.com")).isFalse();
    }

    @Test
    void subdomainsInheritTheirProviderRule() {
        assertThat(ProviderPasswordRules.forProvider("statements.mstock.com"))
            .isEqualTo(ProviderPasswordRules.forProvider("mstock.com"));
        // But a lookalike must not inherit it.
        assertThat(ProviderPasswordRules.isKnown("notmstock.com")).isFalse();
    }

    @Test
    void unknownProvidersFallBackRatherThanGivingUp() {
        assertThat(ProviderPasswordRules.forProvider("somenewbroker.in"))
            .isEqualTo(ProviderPasswordRules.DEFAULT_ORDER);
        assertThat(ProviderPasswordRules.forProvider(null))
            .isEqualTo(ProviderPasswordRules.DEFAULT_ORDER);
    }

    // --- Hint parsing ---

    @Test
    @DisplayName("compound hints beat the bare forms they contain")
    void hintOrderingIsSpecificFirst() {
        PasswordCandidateResolver resolver = new PasswordCandidateResolver(null);

        // "first 4 characters of PAN + date of birth" also contains "pan" and "date of birth";
        // matching the bare form first would attempt the wrong format.
        assertThat(resolver.strategiesFromHint("First four letters of PAN + Date of Birth (DDMMYYYY)"))
            .containsExactly(PasswordStrategy.PAN_FIRST4_PLUS_DOB_DDMMYYYY);
        assertThat(resolver.strategiesFromHint("PAN + Date of Birth (DDMMYYYY)"))
            .containsExactly(PasswordStrategy.PAN_UPPERCASE_PLUS_DOB_DDMMYYYY);
        assertThat(resolver.strategiesFromHint("PAN (uppercase)"))
            .containsExactly(PasswordStrategy.PAN_UPPERCASE);
        assertThat(resolver.strategiesFromHint("Date of Birth (DDMMYYYY)"))
            .containsExactly(PasswordStrategy.DOB_DDMMYYYY);
        assertThat(resolver.strategiesFromHint(null)).isEmpty();
        assertThat(resolver.strategiesFromHint("Customer ID")).isEmpty();
    }

    // --- The security contract ---

    @Test
    @DisplayName("a candidate never leaks its value through toString")
    void candidateToStringIsRedacted() {
        // log.debug("trying {}", candidate) is exactly the line someone adds later without
        // thinking, so the type itself has to make that harmless.
        PasswordCandidate c = new PasswordCandidate(
            PasswordStrategy.PAN_UPPERCASE, "ABCDE1234F", "PAN (uppercase)");

        assertThat(c.toString()).doesNotContain("ABCDE1234F").contains("***");
    }
}
