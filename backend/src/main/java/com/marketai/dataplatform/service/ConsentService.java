package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.provider.ConsentCapableProvider;
import com.marketai.dataplatform.provider.FinancialDataProvider;
import com.marketai.dataplatform.provider.ProviderRegistry;
import com.marketai.dataplatform.repo.ConsentRecordRepository;
import com.marketai.dataplatform.repo.DataConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Consent lifecycle for provider connections: request → pending → approved / rejected, then
 * revoked or expired. A connection becomes CONNECTED only when its consent is APPROVED.
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ProviderRegistry providers;
    private final DataConnectionRepository connections;
    private final ConsentRecordRepository consents;
    private final AuditService audit;

    public record Started(DataConnection connection, ConsentRecord consent, String redirectUrl) {}

    @Transactional
    public Started start(Long userId, String providerId, String institution) {
        FinancialDataProvider p = providers.find(providerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not configured: " + providerId));
        if (!(p instanceof ConsentCapableProvider cp))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, p.displayName() + " does not use consent");

        DataConnection conn = connections.save(DataConnection.builder().userId(userId).providerId(p.providerId())
            .sourceType(p.sourceType()).mode(p.mode()).institution(institution).displayName(
                institution != null && !institution.isBlank() ? institution : p.displayName())
            .status(ConnectionStatus.PENDING_CONSENT).build());
        ConsentCapableProvider.ConsentInitiation init = cp.createConsent(new ConsentCapableProvider.ConsentRequest(
            userId, institution, "Wealth tracking and reconciliation", List.of("DEPOSIT", "MUTUAL_FUNDS", "EQUITIES"),
            LocalDate.now().minusYears(1), LocalDate.now()));
        ConsentRecord c = consents.save(ConsentRecord.builder().userId(userId).connectionId(conn.getId()).providerId(p.providerId())
            .providerConsentHandle(init.consentHandle()).status(init.status()).purpose("Wealth tracking and reconciliation")
            .fiTypes("DEPOSIT,MUTUAL_FUNDS,EQUITIES").requestedAt(LocalDateTime.now()).lastEventAt(LocalDateTime.now())
            .dataRangeFrom(LocalDate.now().minusYears(1)).dataRangeTo(LocalDate.now()).build());
        conn.setConsentId(c.getId());
        connections.save(conn);
        audit.record(userId, String.valueOf(userId), "CONSENT", c.getId(), "REQUESTED", null, init.status().name(), p.providerId());
        return new Started(conn, c, init.redirectUrl());
    }

    /** Applies a provider-reported status to the consent and its connection. */
    @Transactional
    public void applyEvent(String providerId, ConsentCapableProvider.ConsentEvent event) {
        ConsentRecord c = consents.findByProviderIdAndProviderConsentHandle(providerId, event.consentHandle()).orElse(null);
        if (c == null) return;   // not ours: ignore rather than reveal anything
        setStatus(c, event.status());
    }

    /** Asks the provider for the current status (polling fallback when no webhook arrives). */
    @Transactional
    public ConsentRecord refresh(Long userId, Long consentId) {
        ConsentRecord c = consents.findByIdAndUserId(consentId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Consent not found"));
        providers.find(c.getProviderId()).filter(ConsentCapableProvider.class::isInstance).map(ConsentCapableProvider.class::cast)
            .ifPresent(cp -> setStatus(c, cp.consentStatus(c.getProviderConsentHandle())));
        return c;
    }

    @Transactional
    public void revoke(Long userId, Long connectionId) {
        DataConnection conn = connections.findByIdAndUserId(connectionId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        if (conn.getConsentId() != null) consents.findById(conn.getConsentId()).ifPresent(c -> {
            providers.find(c.getProviderId()).filter(ConsentCapableProvider.class::isInstance).map(ConsentCapableProvider.class::cast)
                .ifPresent(cp -> cp.revokeConsent(c.getProviderConsentHandle()));
            setStatus(c, ConsentStatus.REVOKED);
        });
        conn.setStatus(ConnectionStatus.DISCONNECTED);
        connections.save(conn);
    }

    private void setStatus(ConsentRecord c, ConsentStatus status) {
        if (c.getStatus() == status) return;
        ConsentStatus before = c.getStatus();
        c.setStatus(status);
        c.setLastEventAt(LocalDateTime.now());
        if (status == ConsentStatus.APPROVED) c.setApprovedAt(LocalDateTime.now());
        if (status == ConsentStatus.REVOKED) c.setRevokedAt(LocalDateTime.now());
        consents.save(c);
        if (c.getConnectionId() != null) connections.findById(c.getConnectionId()).ifPresent(conn -> {
            conn.setStatus(status == ConsentStatus.APPROVED ? ConnectionStatus.CONNECTED
                : status == ConsentStatus.REQUESTED || status == ConsentStatus.PENDING_APPROVAL ? ConnectionStatus.PENDING_CONSENT
                : status == ConsentStatus.FAILED ? ConnectionStatus.ERROR : ConnectionStatus.DISCONNECTED);
            connections.save(conn);
        });
        audit.record(c.getUserId(), AuditService.SYSTEM, "CONSENT", c.getId(), "STATUS", before.name(), status.name(), c.getProviderId());
    }
}
