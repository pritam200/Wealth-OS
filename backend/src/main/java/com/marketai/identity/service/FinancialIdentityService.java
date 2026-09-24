package com.marketai.identity.service;

import com.marketai.gmail.security.PasswordCipher;
import com.marketai.identity.entity.FinancialIdentity;
import com.marketai.identity.repository.FinancialIdentityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Stores and reads the PAN/DOB used to derive statement passwords.
 *
 * <p>Read access is intentionally narrow: {@link #resolvePan} and {@link #resolveDob} are the
 * only ways to obtain a plaintext value, they are package-visible to the password engine, and
 * nothing else in the application calls them. There is no getter that returns the decrypted PAN
 * to a controller, because the moment one exists it ends up in a DTO and then in a log.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FinancialIdentityService {

    /** Statutory PAN format: five letters, four digits, one letter. */
    private static final Pattern PAN_FORMAT = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    private final FinancialIdentityRepository repo;
    private final PasswordCipher cipher;

    /**
     * Saves or updates the identity. Either field may be omitted to update just the other.
     *
     * @throws IllegalArgumentException if the PAN is malformed or the DOB is not a real past date
     */
    @Transactional
    public void save(Long userId, String pan, LocalDate dob) {
        FinancialIdentity identity = repo.findByUserId(userId)
            .orElseGet(() -> FinancialIdentity.builder().userId(userId).build());

        if (pan != null && !pan.isBlank()) {
            String normalised = pan.trim().toUpperCase().replaceAll("\\s", "");
            if (!PAN_FORMAT.matcher(normalised).matches()) {
                // The message deliberately describes the format and never echoes the input —
                // an invalid-PAN error that quotes the value would put it in the client's
                // error log and, from there, anywhere.
                throw new IllegalArgumentException(
                    "PAN must be 5 letters, 4 digits, then 1 letter (for example ABCDE1234F)");
            }
            identity.setEncryptedPan(cipher.encrypt(normalised));
        }

        if (dob != null) {
            if (dob.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("Date of birth cannot be in the future");
            }
            if (dob.isBefore(LocalDate.now().minusYears(120))) {
                throw new IllegalArgumentException("Date of birth is not a plausible date");
            }
            identity.setEncryptedDob(cipher.encrypt(dob.toString()));
        }

        repo.save(identity);
        // Logs the fact, never the values.
        log.info("Financial identity updated for user {} (pan={}, dob={})",
            userId, identity.hasPan() ? "present" : "absent", identity.hasDob() ? "present" : "absent");
    }

    /** Whether anything is stored — the only identity fact safe to expose over an API. */
    public boolean hasPan(Long userId) {
        return repo.findByUserId(userId).map(FinancialIdentity::hasPan).orElse(false);
    }

    public boolean hasDob(Long userId) {
        return repo.findByUserId(userId).map(FinancialIdentity::hasDob).orElse(false);
    }

    /** True when at least one derived password strategy could be attempted. */
    public boolean canDeriveAnyPassword(Long userId) {
        return repo.findByUserId(userId)
            .map(i -> i.hasPan() || i.hasDob())
            .orElse(false);
    }

    @Transactional
    public void clear(Long userId) {
        repo.deleteByUserId(userId);
        log.info("Financial identity cleared for user {}", userId);
    }

    @Transactional
    public void recordSuccessfulUse(Long userId) {
        repo.findByUserId(userId).ifPresent(i -> {
            i.setLastUsedAt(java.time.LocalDateTime.now());
            repo.save(i);
        });
    }

    /**
     * Derives the statement password for one specific {@link PasswordStrategy}, from the user's
     * stored PAN/DOB. Returns empty when the strategy's required input isn't stored, or when the
     * strategy is {@link PasswordStrategy#CUSTOM_SAVED} (not derivable from identity at all).
     *
     * <p>This is the only way a caller outside this package can turn a strategy into an actual
     * password. It exists for the LLM-driven password flow ({@code EmailLLMParserService} /
     * {@code PdfImportService}): the model only ever decides <em>which</em> strategy applies —
     * it never sees or produces PAN, DOB, or the derived password itself. The value is produced
     * here, deterministically, from the vault.
     */
    public Optional<String> derivePassword(Long userId, PasswordStrategy strategy) {
        if (strategy == null || strategy == PasswordStrategy.CUSTOM_SAVED) return Optional.empty();

        String pan = null;
        if (strategy.needsPan()) {
            pan = resolvePan(userId).orElse(null);
            if (pan == null) return Optional.empty();
        }
        LocalDate dob = null;
        if (strategy.needsDob()) {
            dob = resolveDob(userId).orElse(null);
            if (dob == null) return Optional.empty();
        }
        return Optional.ofNullable(strategy.derive(pan, dob));
    }

    // --- Narrow plaintext access, for the password engine only ---

    Optional<String> resolvePan(Long userId) {
        return repo.findByUserId(userId)
            .filter(FinancialIdentity::hasPan)
            .map(i -> safeDecrypt(i.getEncryptedPan()));
    }

    Optional<LocalDate> resolveDob(Long userId) {
        return repo.findByUserId(userId)
            .filter(FinancialIdentity::hasDob)
            .map(i -> safeDecrypt(i.getEncryptedDob()))
            .map(this::parseDob)
            .filter(java.util.Objects::nonNull);
    }

    private String safeDecrypt(String ciphertext) {
        try {
            return cipher.decrypt(ciphertext);
        } catch (RuntimeException e) {
            // A rotated or mismatched key must degrade to "no credential available" rather than
            // propagating an exception through the sync. The message is logged without the
            // ciphertext, which is still key-derived material.
            log.error("Could not decrypt a stored financial identity value — check that the "
                + "encryption key has not changed. Treating it as absent.");
            return null;
        }
    }

    private LocalDate parseDob(String iso) {
        if (iso == null) return null;
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException e) {
            log.error("Stored date of birth is not in the expected ISO format — treating as absent");
            return null;
        }
    }
}
