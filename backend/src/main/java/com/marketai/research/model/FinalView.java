package com.marketai.research.model;

/**
 * The research conclusion shown to users, decided by {@code FinalViewPolicy} from the
 * quantitative view and the research — a fixed, tested gate, not a weighted score.
 *
 * @param actionability BUY | SELL | NO_ACTIONABLE_SIGNAL | CONFLICTING_EVIDENCE | RESEARCH_REQUIRED | INSUFFICIENT_DATA
 * @param rule          which rule of the policy produced it, for the audit trail
 */
public record FinalView(String actionability, String reason, String rule, String quantRating, String researchLean) {}
