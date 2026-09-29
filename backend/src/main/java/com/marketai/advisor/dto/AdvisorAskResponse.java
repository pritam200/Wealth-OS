package com.marketai.advisor.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code groundedData} is the exact real-data payload the answer was built from (whatever the
 * resolved {@link AdvisorTool} returned) — the UI can render it directly, and it is the proof
 * that {@code answer} did not fabricate a figure. Empty when {@code tool} is {@code UNKNOWN} or
 * unavailable, never populated with a guess.
 */
@Data
@Builder
public class AdvisorAskResponse {
    private String answer;
    private AdvisorTool tool;
    private Map<String, Object> groundedData;
    /** The records behind the answer, each with a way to its source document. */
    @Builder.Default
    private List<AdvisorEvidence> evidence = List.of();
    private boolean available;
    private LocalDateTime generatedAt;
}
