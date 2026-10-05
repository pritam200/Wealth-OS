package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.RawRecord;
import com.marketai.dataplatform.repo.RawFinancialDataRepository;
import com.marketai.gmail.security.PasswordCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The raw layer. A record is stored exactly as received, encrypted, before anything interprets
 * it, and a record already stored is recognised rather than stored again — so every ingestion
 * path is idempotent. The idempotency key is the source's own record id when it has one, and the
 * payload hash when it does not.
 */
@Service
@RequiredArgsConstructor
public class RawDataStore {

    private final RawFinancialDataRepository repo;
    private final PasswordCipher cipher;

    public record Stored(RawFinancialData row, boolean created) {}

    public Stored store(Long userId, Long connectionId, Long syncRunId, SourceType type, String provider, ProviderMode mode, RawRecord rec) {
        String payload = rec.payload() == null ? "" : rec.payload();
        String payloadHash = sha256(payload);
        String idKey = sha256(String.join("|", type.name(), provider.toLowerCase(), rec.kind().name(),
            nz(rec.externalAccountId()), rec.externalRecordId() != null ? "id:" + rec.externalRecordId() : "h:" + payloadHash));
        Optional<RawFinancialData> existing = repo.findByUserIdAndIdempotencyKey(userId, idKey);
        if (existing.isPresent()) return new Stored(existing.get(), false);
        RawFinancialData row = repo.save(RawFinancialData.builder()
            .userId(userId).connectionId(connectionId).syncRunId(syncRunId)
            .sourceType(type).sourceProvider(provider).providerMode(mode).recordKind(rec.kind())
            .externalAccountId(rec.externalAccountId()).externalRecordId(rec.externalRecordId())
            .payload(cipher.encrypt(payload)).payloadHash(payloadHash).idempotencyKey(idKey)
            .schemaVersion(rec.schemaVersion())
            .receivedAt(rec.receivedAt() == null ? LocalDateTime.now() : rec.receivedAt())
            .processingStatus(ProcessingStatus.RECEIVED).build());
        return new Stored(row, true);
    }

    /** The payload as received, decrypted — for reprocessing and debugging only. */
    public String payload(RawFinancialData row) { return row.getPayload() == null ? "" : cipher.decrypt(row.getPayload()); }

    public void mark(RawFinancialData row, ProcessingStatus status, String error) {
        row.setProcessingStatus(status);
        row.setError(error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error);
        if (status == ProcessingStatus.PROCESSED || status == ProcessingStatus.REJECTED || status == ProcessingStatus.DUPLICATE_PAYLOAD)
            row.setProcessedAt(LocalDateTime.now());
        repo.save(row);
    }

    static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
