package com.marketai.dataplatform.provider;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.RawRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in aggregator for development and tests. It is MOCK in every place that matters: its
 * {@link #mode()} is MOCK, the records it produces are stored as MOCK, and the UI labels it so.
 * It returns only the fixtures a developer or test loads into it — it has no built-in portfolio,
 * so enabling it can never put invented holdings into a real user's ledger.
 *
 * <p>Enabled only by {@code wealthos.data.providers.mock.enabled=true}; absent otherwise.
 */
@Component
@ConditionalOnProperty(name = "wealthos.data.providers.mock.enabled", havingValue = "true")
public class MockAccountAggregatorProvider implements ConsentCapableProvider {

    public static final String ID = "mock-aa";

    private final Map<String, ConsentStatus> consents = new ConcurrentHashMap<>();
    private final Map<Long, List<RawRecord>> fixtures = new ConcurrentHashMap<>();

    @Override public String providerId() { return ID; }
    @Override public String displayName() { return "Mock Account Aggregator (development)"; }
    @Override public SourceType sourceType() { return SourceType.ACCOUNT_AGGREGATOR; }
    @Override public ProviderMode mode() { return ProviderMode.MOCK; }

    /** Replaces what {@code userId}'s sync will return. */
    public void useFixtures(Long userId, List<RawRecord> records) { fixtures.put(userId, List.copyOf(records)); }

    /** Plays the part of the user approving the consent at the provider. */
    public void approve(String handle) { consents.computeIfPresent(handle, (h, s) -> ConsentStatus.APPROVED); }
    public void reject(String handle) { consents.computeIfPresent(handle, (h, s) -> ConsentStatus.REJECTED); }

    @Override
    public ConsentInitiation createConsent(ConsentRequest request) {
        String handle = "mock-consent-" + UUID.randomUUID();
        consents.put(handle, ConsentStatus.PENDING_APPROVAL);
        return new ConsentInitiation(handle, null, ConsentStatus.PENDING_APPROVAL);
    }

    @Override public ConsentStatus consentStatus(String handle) { return consents.getOrDefault(handle, ConsentStatus.FAILED); }

    @Override public void revokeConsent(String handle) { consents.computeIfPresent(handle, (h, s) -> ConsentStatus.REVOKED); }

    @Override
    public FetchResult fetch(FetchRequest request) {
        if (request.consentHandle() == null || !consentStatus(request.consentHandle()).usable())
            throw new IllegalStateException("consent is not approved");
        List<RawRecord> all = fixtures.getOrDefault(request.userId(), List.of());
        int from = 0;
        if (request.cursor() != null) {
            try { from = Math.min(all.size(), Integer.parseInt(request.cursor())); } catch (NumberFormatException ignored) { }
        }
        return new FetchResult(all.subList(from, all.size()), String.valueOf(all.size()), request.from(), request.to());
    }

    /** Signature-less by design: a mock has nothing to verify, so only a body of the form {@code handle|STATUS} is read. */
    @Override
    public Optional<ConsentEvent> parseCallback(Map<String, String> headers, String body) {
        if (body == null) return Optional.empty();
        String[] p = body.trim().split("\\|");
        if (p.length != 2) return Optional.empty();
        try { return Optional.of(new ConsentEvent(p[0], ConsentStatus.valueOf(p[1]), "mock callback")); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
