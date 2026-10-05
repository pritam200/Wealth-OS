package com.marketai.dataplatform.provider;

import com.marketai.dataplatform.domain.ConsentStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The consent lifecycle of an Account Aggregator style provider: ask the user's consent,
 * learn its outcome (by polling or by webhook), and revoke it. Authorisation with the
 * institution happens at the provider; none of its credentials pass through here.
 */
public interface ConsentCapableProvider extends FinancialDataProvider {

    @Override default boolean requiresConsent() { return true; }

    record ConsentRequest(Long userId, String institution, String purpose, List<String> fiTypes, LocalDate from, LocalDate to) {}

    /** @param redirectUrl where the user authorises with the provider; null if the provider has no hosted step */
    record ConsentInitiation(String consentHandle, String redirectUrl, ConsentStatus status) {}

    record ConsentEvent(String consentHandle, ConsentStatus status, String detail) {}

    ConsentInitiation createConsent(ConsentRequest request);

    ConsentStatus consentStatus(String consentHandle);

    void revokeConsent(String consentHandle);

    /**
     * Verifies and parses a provider callback. Must return empty — never throw, never trust —
     * when the signature does not verify; the endpoint treats empty as "reject".
     */
    Optional<ConsentEvent> parseCallback(Map<String, String> headers, String body);
}
