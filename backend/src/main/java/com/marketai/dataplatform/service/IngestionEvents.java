package com.marketai.dataplatform.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Structured events for the ingestion pipeline: one log line per event in {@code key=value} form,
 * and a counter per event name when metrics are available.
 *
 * <p>Fields are identifiers and counts only. Payloads, amounts-with-identity, tokens and
 * credentials are never passed in, so there is nothing sensitive here to leak.
 */
@Component
@Slf4j
public class IngestionEvents {

    public static final String SYNC_STARTED = "sync_started";
    public static final String SYNC_COMPLETED = "sync_completed";
    public static final String SYNC_FAILED = "sync_failed";
    public static final String RECORD_RECEIVED = "record_received";
    public static final String RECORD_NORMALIZED = "record_normalized";
    public static final String RECORD_DEDUPLICATED = "record_deduplicated";
    public static final String TRANSACTION_CREATED = "transaction_created";
    public static final String TRANSACTION_RECONCILED = "transaction_reconciled";
    public static final String TRANSACTION_CONFLICT = "transaction_conflict";
    public static final String HOLDING_MISMATCH = "holding_mismatch";
    public static final String MISSING_TRANSACTION = "missing_transaction";

    private final ObjectProvider<MeterRegistry> registry;

    public IngestionEvents(ObjectProvider<MeterRegistry> registry) { this.registry = registry; }

    public void emit(String event, Object... keyValues) {
        StringBuilder sb = new StringBuilder("event=").append(event);
        for (int i = 0; i + 1 < keyValues.length; i += 2) sb.append(' ').append(keyValues[i]).append('=').append(keyValues[i + 1]);
        log.info(sb.toString());
        MeterRegistry r = registry.getIfAvailable();
        if (r != null) r.counter("wealthos.ingestion", "event", event).increment();
    }
}
