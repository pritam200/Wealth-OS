package com.marketai.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the ordered, bounded list of passwords to attempt for a locked document.
 *
 * <p>Three sources feed it, in descending order of evidence:
 *
 * <ol>
 *   <li><b>A saved credential</b> for this provider — the user has already proven it works.</li>
 *   <li><b>An instruction in the email body</b> — the issuer stated the format outright, which
 *       beats any assumption about the provider.</li>
 *   <li><b>The provider rule table</b> — the published convention for that sender.</li>
 * </ol>
 *
 * <p><b>This is not a brute-force search and must never become one.</b> The candidate set is
 * capped at {@link #MAX_ATTEMPTS}, every entry traces to one of the three sources above, and
 * strategies whose inputs are missing drop out rather than contributing malformed guesses.
 * Beyond correctness, that bound matters operationally: repeated failed attempts against an
 * issuer portal are indistinguishable from an attack, and some providers rate-limit or lock.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PasswordCandidateResolver {

    /**
     * Hard ceiling on attempts per document. Four is enough for the longest legitimate provider
     * rule (depository eCAS: first4+DOB, PAN, DOB) plus a saved credential, and small enough
     * that a wrong stored PAN fails fast and visibly instead of grinding.
     */
    public static final int MAX_ATTEMPTS = 4;

    private final FinancialIdentityService identityService;

    /**
     * @param providerKey    sender domain, e.g. {@code camsonline.com}
     * @param emailBodyHint  the hint {@code PasswordHintExtractor} derived from the email body,
     *                       or null
     * @param savedPassword  a previously-saved working credential for this provider, or null
     */
    public List<PasswordCandidate> resolve(Long userId, String providerKey,
                                           String emailBodyHint, String savedPassword) {
        List<PasswordCandidate> candidates = new ArrayList<>();

        // 1. A credential the user already proved works. Always first — it needs no inference.
        if (savedPassword != null && !savedPassword.isBlank()) {
            candidates.add(new PasswordCandidate(
                PasswordStrategy.CUSTOM_SAVED, savedPassword, "saved credential for " + providerKey));
        }

        Optional<String> pan = identityService.resolvePan(userId);
        Optional<LocalDate> dob = identityService.resolveDob(userId);

        if (pan.isEmpty() && dob.isEmpty()) {
            // Nothing to derive from. Returning just the saved credential (if any) is correct —
            // the caller reports "password could not be resolved" and prompts for the identity.
            return capped(candidates);
        }

        // Ordered set so an email hint and a provider rule proposing the same strategy produce
        // one attempt, not two. Insertion order is preserved, so evidence strength survives.
        Set<PasswordStrategy> ordered = new LinkedHashSet<>();

        // 2. What the email itself said, ahead of the provider default.
        ordered.addAll(strategiesFromHint(emailBodyHint));

        // 3. The provider's published convention.
        ordered.addAll(ProviderPasswordRules.forProvider(providerKey));

        for (PasswordStrategy strategy : ordered) {
            if (strategy.needsPan() && pan.isEmpty()) continue;
            if (strategy.needsDob() && dob.isEmpty()) continue;

            String derived = strategy.derive(pan.orElse(null), dob.orElse(null));
            if (derived == null || derived.isBlank()) continue;

            boolean alreadyQueued = candidates.stream().anyMatch(c -> derived.equals(c.value()));
            if (alreadyQueued) continue;

            candidates.add(new PasswordCandidate(strategy, derived, strategy.describe()));
        }

        return capped(candidates);
    }

    /**
     * Maps a free-text hint onto concrete strategies.
     *
     * <p>Order within this method matters: the compound forms are tested before the bare ones,
     * because "first four characters of your PAN followed by your date of birth" also contains
     * the substrings "PAN" and "date of birth", and matching the bare form first would attempt
     * the wrong format and waste an attempt.
     */
    List<PasswordStrategy> strategiesFromHint(String hint) {
        if (hint == null || hint.isBlank()) return List.of();
        String h = hint.toLowerCase(Locale.ROOT);

        boolean mentionsPan = h.contains("pan");
        boolean mentionsDob = h.contains("date of birth") || h.contains("dob");

        if (mentionsPan && mentionsDob) {
            if (h.contains("first 4") || h.contains("first four")) {
                return List.of(PasswordStrategy.PAN_FIRST4_PLUS_DOB_DDMMYYYY);
            }
            if (h.contains("first 5") || h.contains("first five")) {
                return List.of(PasswordStrategy.PAN_FIRST5_PLUS_DOB_DDMMYYYY);
            }
            return List.of(PasswordStrategy.PAN_UPPERCASE_PLUS_DOB_DDMMYYYY);
        }
        if (mentionsPan) return List.of(PasswordStrategy.PAN_UPPERCASE);
        if (mentionsDob) return List.of(PasswordStrategy.DOB_DDMMYYYY);
        return List.of();
    }

    private List<PasswordCandidate> capped(List<PasswordCandidate> candidates) {
        if (candidates.size() <= MAX_ATTEMPTS) return List.copyOf(candidates);
        log.debug("Capping password candidates from {} to {}", candidates.size(), MAX_ATTEMPTS);
        return List.copyOf(candidates.subList(0, MAX_ATTEMPTS));
    }

    /** What to tell the user when nothing could be resolved — never mentions a value. */
    public String explainFailure(Long userId, String providerKey) {
        boolean hasPan = identityService.hasPan(userId);
        boolean hasDob = identityService.hasDob(userId);

        if (ProviderPasswordRules.isUserDefinedOnly(providerKey)) {
            return "This provider uses a password you chose when requesting the statement, so it "
                 + "cannot be derived. Enter it once and it can be saved for future statements.";
        }
        if (!hasPan && !hasDob) {
            return "Statement locked — add your PAN and date of birth under Financial Identity "
                 + "so statement passwords can be derived automatically.";
        }
        if (!hasPan) {
            return "Statement locked — this provider's statements use PAN, which is not saved yet.";
        }
        if (!hasDob) {
            return "Statement locked — this provider's statements use date of birth, which is not saved yet.";
        }
        return "Statement locked — the saved PAN and date of birth did not open it. "
             + "Check they are correct, or enter the password once to save it for this provider.";
    }
}
