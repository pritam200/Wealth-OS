package com.marketai.identity.service;

import com.marketai.gmail.security.PasswordCipher;
import com.marketai.identity.entity.FinancialIdentity;
import com.marketai.identity.repository.FinancialIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The vault's security contract, which matters more than its behaviour: PAN is a government
 * identifier and DOB is sensitive personal data, and both exist here only to derive statement
 * passwords.
 */
class FinancialIdentityVaultTest {

    private static final Long USER = 1L;
    private static final String PAN = "ABCDE1234F";
    private static final LocalDate DOB = LocalDate.of(1990, 3, 7);

    private FinancialIdentityRepository repo;
    private FinancialIdentityService service;
    private PasswordCipher cipher;

    @BeforeEach
    void setUp() {
        repo = mock(FinancialIdentityRepository.class);
        cipher = new PasswordCipher();
        // Fixed 32-byte AES-256 key so the test never touches the real key file.
        ReflectionTestUtils.setField(cipher, "configuredKeyB64",
            java.util.Base64.getEncoder().encodeToString(new byte[32]));
        ReflectionTestUtils.invokeMethod(cipher, "init");

        service = new FinancialIdentityService(repo, cipher);
        when(repo.findByUserId(USER)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private FinancialIdentity captureSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(FinancialIdentity.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("PAN and DOB are encrypted at rest — plaintext never reaches the database")
    void valuesAreEncryptedNotStoredPlaintext() {
        service.save(USER, PAN, DOB);
        FinancialIdentity saved = captureSaved();

        assertThat(saved.getEncryptedPan()).isNotNull().doesNotContain(PAN);
        assertThat(saved.getEncryptedDob()).isNotNull().doesNotContain("1990");
        // And it must round-trip, or the whole feature is a write-only hole.
        assertThat(cipher.decrypt(saved.getEncryptedPan())).isEqualTo(PAN);
        assertThat(cipher.decrypt(saved.getEncryptedDob())).isEqualTo("1990-03-07");
    }

    @Test
    @DisplayName("PAN is normalised to uppercase before encryption")
    void panIsNormalised() {
        // Providers use PAN-in-uppercase; storing it as typed would derive a wrong password
        // from a correct PAN.
        service.save(USER, "  abcde1234f  ", null);

        assertThat(cipher.decrypt(captureSaved().getEncryptedPan())).isEqualTo("ABCDE1234F");
    }

    @Test
    @DisplayName("a malformed PAN is rejected, and the error never echoes the value back")
    void malformedPanIsRejectedWithoutEchoing() {
        // An error that quotes the input puts it in the client's error log, and from there
        // anywhere — so the message describes the format instead.
        assertThatThrownBy(() -> service.save(USER, "NOTAPAN123", DOB))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("NOTAPAN123")
            .hasMessageContaining("5 letters, 4 digits");

        assertThatThrownBy(() -> service.save(USER, "ABCDE1234", DOB))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void implausibleDatesAreRejected() {
        assertThatThrownBy(() -> service.save(USER, null, LocalDate.now().plusDays(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("future");
        assertThatThrownBy(() -> service.save(USER, null, LocalDate.now().minusYears(150)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("either field can be updated alone, since the client can never read the other back")
    void partialUpdatesArePossible() {
        FinancialIdentity existing = FinancialIdentity.builder()
            .userId(USER).encryptedPan(cipher.encrypt(PAN)).build();
        when(repo.findByUserId(USER)).thenReturn(Optional.of(existing));

        service.save(USER, null, DOB);
        FinancialIdentity saved = captureSaved();

        assertThat(cipher.decrypt(saved.getEncryptedPan())).isEqualTo(PAN);   // untouched
        assertThat(saved.hasDob()).isTrue();
    }

    @Test
    @DisplayName("a rotated encryption key degrades to 'no credential', not an exception")
    void undecryptableValueIsTreatedAsAbsent() {
        // A key change must not throw out of the middle of a Gmail sync.
        when(repo.findByUserId(USER)).thenReturn(Optional.of(FinancialIdentity.builder()
            .userId(USER).encryptedPan("not-valid-ciphertext").build()));

        assertThat(service.resolvePan(USER)).isEmpty();
    }

    @Test
    @DisplayName("status reports presence only — there is no API that returns the value")
    void onlyPresenceIsExposed() {
        when(repo.findByUserId(USER)).thenReturn(Optional.of(FinancialIdentity.builder()
            .userId(USER).encryptedPan(cipher.encrypt(PAN)).build()));

        assertThat(service.hasPan(USER)).isTrue();
        assertThat(service.hasDob(USER)).isFalse();
        assertThat(service.canDeriveAnyPassword(USER)).isTrue();

        var status = com.marketai.identity.dto.FinancialIdentityStatus.of(true, false);
        // The DTO has no field capable of carrying the value, by construction.
        assertThat(status.toString()).doesNotContain(PAN);
    }

    // --- The behaviour the audit found missing, now exposed via derivePassword() for the
    // LLM-driven flow (EmailLLMParserService only ever names a PasswordStrategy; this is where
    // that strategy actually becomes a password) ---

    @Test
    @DisplayName("with a stored PAN, a PAN-uppercase password is derived without any saved credential")
    void derivesPasswordForFirstTimeUser() {
        when(repo.findByUserId(USER)).thenReturn(Optional.of(FinancialIdentity.builder()
            .userId(USER).encryptedPan(cipher.encrypt(PAN)).encryptedDob(cipher.encrypt(DOB.toString()))
            .build()));

        // Previously this would have been empty for a user who had never saved a password, and
        // the statement stayed locked forever.
        assertThat(service.derivePassword(USER, PasswordStrategy.PAN_UPPERCASE)).contains("ABCDE1234F");
        assertThat(service.derivePassword(USER, PasswordStrategy.PAN_UPPERCASE_PLUS_DOB_DDMMYYYY))
            .contains("ABCDE1234F07031990");
    }

    @Test
    @DisplayName("a strategy needing PAN yields nothing when only DOB is stored")
    void derivePasswordRespectsMissingInputs() {
        when(repo.findByUserId(USER)).thenReturn(Optional.of(FinancialIdentity.builder()
            .userId(USER).encryptedDob(cipher.encrypt(DOB.toString())).build()));

        assertThat(service.derivePassword(USER, PasswordStrategy.PAN_UPPERCASE)).isEmpty();
        assertThat(service.derivePassword(USER, PasswordStrategy.DOB_DDMMYYYY)).contains("07031990");
    }

    @Test
    @DisplayName("CUSTOM_SAVED and a null strategy are never derivable from identity")
    void derivePasswordRefusesNonDerivableStrategies() {
        when(repo.findByUserId(USER)).thenReturn(Optional.of(FinancialIdentity.builder()
            .userId(USER).encryptedPan(cipher.encrypt(PAN)).encryptedDob(cipher.encrypt(DOB.toString()))
            .build()));

        assertThat(service.derivePassword(USER, PasswordStrategy.CUSTOM_SAVED)).isEmpty();
        assertThat(service.derivePassword(USER, null)).isEmpty();
    }
}
