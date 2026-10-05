package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.provider.FinancialDataProvider;
import com.marketai.dataplatform.provider.ProviderRegistry;
import com.marketai.dataplatform.repo.ConsentRecordRepository;
import com.marketai.dataplatform.repo.DataConnectionRepository;
import com.marketai.gmail.repository.GmailTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What the Data Connections screen shows: every source feeding the ledger, honestly labelled.
 * Gmail is listed as what it is — a signal source — and a MOCK or TEST provider is labelled as
 * such, never as a live institution.
 */
@Service
@RequiredArgsConstructor
public class DataConnectionService {

    private final DataConnectionRepository connections;
    private final ConsentRecordRepository consents;
    private final ProviderRegistry providers;
    private final GmailTokenRepository gmail;

    public record ConnectionView(Long id, String providerId, String displayName, String institution, SourceType sourceType,
                                 ProviderMode mode, ConnectionStatus status, ConsentStatus consentStatus, Long consentId,
                                 LocalDateTime lastSyncAt, LocalDateTime lastSuccessfulSyncAt, SyncStatus syncStatus,
                                 String lastError, boolean authoritative, String note) {}

    public record ProviderView(String providerId, String displayName, SourceType sourceType, ProviderMode mode, boolean requiresConsent) {}

    public record Overview(List<ConnectionView> connections, List<ProviderView> availableProviders, boolean institutionSourceConnected,
                           String message) {}

    public Overview overview(Long userId) {
        List<ConnectionView> out = new ArrayList<>();
        boolean institution = false;
        for (DataConnection c : connections.findByUserIdOrderByIdAsc(userId)) {
            ConsentStatus cs = c.getConsentId() == null ? null : consents.findById(c.getConsentId()).map(ConsentRecord::getStatus).orElse(null);
            boolean auth = c.getSourceType().authoritative();
            if (auth && c.getMode() == ProviderMode.LIVE && c.getStatus() == ConnectionStatus.CONNECTED) institution = true;
            String note = c.getMode() == ProviderMode.LIVE ? null
                : "This is a " + c.getMode() + " provider for development. It is not a live connection to " + (c.getInstitution() == null ? "an institution" : c.getInstitution()) + ".";
            out.add(new ConnectionView(c.getId(), c.getProviderId(), c.getDisplayName(), c.getInstitution(), c.getSourceType(), c.getMode(),
                c.getStatus(), cs, c.getConsentId(), c.getLastSyncAt(), c.getLastSuccessfulSyncAt(), c.getSyncStatus(), c.getLastError(), auth, note));
        }
        gmail.findByUserId(userId).ifPresent(t -> out.add(new ConnectionView(null, "gmail", "Gmail", t.getConnectedEmail(), SourceType.EMAIL, ProviderMode.LIVE,
            ConnectionStatus.CONNECTED, null, null, t.getLastSyncAt(), t.getLastSyncAt(), SyncStatus.IDLE, null, false,
            "Email is a signal, not a source of truth. Transactions found in email stay unconfirmed until an institution source verifies them.")));
        List<ProviderView> available = providers.all().stream()
            .map(p -> new ProviderView(p.providerId(), p.displayName(), p.sourceType(), p.mode(), p.requiresConsent())).toList();
        String message = institution ? null : "No live institution source is connected, so transactions cannot be verified yet. "
            + (available.isEmpty() ? "No Account Aggregator or broker provider is configured on this server." : "Connect one below.")
            + " You can also import a statement or CAS file. Email on its own is only a signal, never proof.";
        return new Overview(out, available, institution, message);
    }

    public FinancialDataProvider requireProvider(String id) {
        return providers.find(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND, "Provider not configured"));
    }
}
