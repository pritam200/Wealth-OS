package com.marketai.research.model;

import java.util.List;

/**
 * Everything research is allowed to know, assembled by code before any model is called.
 *
 * @param subjectType STOCK | INDEX | MUTUAL_FUND
 * @param marketDate  the session the prices are from
 * @param snapshotHash SHA-256 of the facts, evidence and quantitative view (not retrieval times) —
 *                     the cache key: new data or news gives a new hash and fresh research
 */
public record ResearchContext(String subjectType, String symbol, String displayName, String marketDate,
                              List<Fact> facts, List<Evidence> evidence, List<SourceStatus> sources,
                              List<String> missing, QuantAssessment quant, Long userId, String snapshotHash) {

    public boolean dataUsable() {
        return quant == null || !("STALE_DATA".equals(quant.seriesStatus()) || "INSUFFICIENT_DATA".equals(quant.seriesStatus()));
    }
}
