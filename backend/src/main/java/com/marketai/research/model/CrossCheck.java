package com.marketai.research.model;

/**
 * The research's answer to one fixed cross-check question.
 *
 * @param question one of {@link #QUESTIONS}' keys
 * @param answer   SUPPORTS | CONTRADICTS | MIXED | UNKNOWN
 */
public record CrossCheck(String question, String label, String answer, Claim explanation) {

    public static final java.util.Map<String, String> QUESTIONS = new java.util.LinkedHashMap<>();
    static {
        QUESTIONS.put("FUNDAMENTALS_VS_TECHNICALS", "Does the fundamental picture support the technical picture?");
        QUESTIONS.put("NEWS_VS_TREND", "Does current news support or contradict the trend?");
        QUESTIONS.put("VALUATION_VS_PRICE", "Does valuation support the current price?");
        QUESTIONS.put("EVENT_DRIVEN_MOVE", "Is the current move event-driven?");
        QUESTIONS.put("SECTOR_CONFIRMATION", "Is the sector confirming the move?");
        QUESTIONS.put("MARKET_CONFIRMATION", "Is the broader market confirming the move?");
        QUESTIONS.put("UNSEEN_RISKS", "Are there material risks the technical model cannot see?");
    }
    public static final java.util.Set<String> ANSWERS = java.util.Set.of("SUPPORTS", "CONTRADICTS", "MIXED", "UNKNOWN");
}
