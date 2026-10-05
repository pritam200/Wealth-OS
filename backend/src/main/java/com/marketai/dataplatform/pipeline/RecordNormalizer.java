package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.SourceType;

import java.util.List;

/**
 * Turns one raw payload of a known schema into normalised records. One implementation per
 * source schema: a new provider adds a normaliser for its own payloads and nothing else changes.
 */
public interface RecordNormalizer {

    boolean supports(String schemaVersion);

    /** @throws IllegalArgumentException when the payload is not valid for the schema */
    List<NormalizedTransaction> transactions(RawRecord raw, SourceType sourceType, String provider);

    /** Empty unless the schema carries holdings. */
    default List<NormalizedHolding> holdings(RawRecord raw, SourceType sourceType, String provider) { return List.of(); }
}
