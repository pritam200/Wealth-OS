package com.marketai.research.model;

import java.util.List;

/**
 * A statement from the research, with what it rests on.
 *
 * @param evidence fact and evidence ids it cites ("F3", "E7"); only ids present in the context survive validation
 * @param basis    DATA | NEWS | FILING | CALCULATION | PORTFOLIO | MODEL | LLM_INTERPRETATION —
 *                 derived from the cited items, never taken from the model; a claim that cites
 *                 nothing valid is LLM_INTERPRETATION
 */
public record Claim(String text, List<String> evidence, String basis) {
    public Claim {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
