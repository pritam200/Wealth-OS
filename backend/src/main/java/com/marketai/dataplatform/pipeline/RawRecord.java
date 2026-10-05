package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.RecordKind;

import java.time.LocalDateTime;

/**
 * One record exactly as a source delivered it, before interpretation. {@code externalRecordId} is
 * the source's own id for it when there is one; it makes re-delivery idempotent.
 */
public record RawRecord(RecordKind kind, String externalAccountId, String externalRecordId,
                        String payload, String schemaVersion, LocalDateTime receivedAt) {
    public RawRecord(RecordKind kind, String externalAccountId, String externalRecordId, String payload, String schemaVersion) {
        this(kind, externalAccountId, externalRecordId, payload, schemaVersion, LocalDateTime.now());
    }
}
