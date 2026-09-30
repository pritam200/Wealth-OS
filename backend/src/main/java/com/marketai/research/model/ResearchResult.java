package com.marketai.research.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The canonical research result every screen consumes (stock page, Market Forecast, AI Advisor,
 * recommendations, Today's Actions). The quantitative view is always present; the research
 * sections are null when no model could answer.
 */
@Data @Builder(toBuilder = true) @NoArgsConstructor @AllArgsConstructor
public class ResearchResult {
    private String subjectType;
    private String symbol;
    private String displayName;
    /** The user whose portfolio was in the context; null when none was. */
    private Long userScope;
    /**
     * OK — both passes validated. PARTIAL — the analyst pass validated, the devil's-advocate pass
     * did not. UNAVAILABLE — no model answered (or its output failed validation). DISABLED — AI is
     * off. NOT_RUN — a cache-only read found nothing.
     */
    private String status;
    private String statusReason;
    private LocalDateTime researchTimestamp;
    private String marketDate;
    /** True when this is an earlier research shown because fresh research was not possible, or
     *  because it was produced for an older session or data snapshot. */
    private boolean stale;
    private String staleReason;
    private boolean fromCache;
    private String provider;
    private String model;
    private boolean fallbackUsed;
    private String analystPromptVersion;
    private String reviewPromptVersion;
    private String webPromptVersion;
    private String dataSnapshotHash;

    private QuantAssessment quant;
    private List<Fact> facts;
    private List<Evidence> evidence;
    private List<SourceStatus> sources;
    private List<String> missingData;

    private ResearchReport report;
    private DevilsAdvocate devilsAdvocate;
    private FinalView finalView;
    private GuardReport guard;
    private List<String> webSearchQueries;
    private Long latencyMs;
    /** When there is no current research: the most recent research, made on older data. */
    private ResearchResult previous;
}
