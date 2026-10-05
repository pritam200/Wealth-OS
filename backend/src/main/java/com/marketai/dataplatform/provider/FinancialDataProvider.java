package com.marketai.dataplatform.provider;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.RawRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * A source of financial data: an aggregator, a broker API, a depository feed. The core domain
 * knows only this interface, so a real integration is a new implementation and nothing else.
 *
 * <p>An implementation returns records exactly as its provider delivers them, tagged with the
 * schema they follow; normalisation is not its job. It must never return, log or store a bank
 * password, OTP or access token.
 */
public interface FinancialDataProvider {

    /** Stable identifier, stored on connections and raw records. */
    String providerId();

    String displayName();

    SourceType sourceType();

    /** MOCK and TEST providers must never be presented as a live institution connection. */
    ProviderMode mode();

    /** Whether the user must grant consent before data can be fetched (true for aggregators). */
    default boolean requiresConsent() { return false; }

    FetchResult fetch(FetchRequest request);

    record FetchRequest(Long userId, Long connectionId, String consentHandle, String cursor, LocalDate from, LocalDate to, SyncKind kind) {}

    /**
     * @param coverageFrom/coverageTo the period the provider asserts it covered, which lets the
     *                       ledger flag entries the institution should have listed but did not
     * @param nextCursor     where an incremental sync resumes
     */
    record FetchResult(List<RawRecord> records, String nextCursor, LocalDate coverageFrom, LocalDate coverageTo) {
        public static FetchResult of(List<RawRecord> records) { return new FetchResult(records, null, null, null); }
    }
}
