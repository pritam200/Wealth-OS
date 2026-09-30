package com.marketai.research.model;

import java.util.List;

/**
 * The deterministic engines' view, exactly as they computed it. Research reads this; it never
 * writes to it — forecasts, levels and signals are the same with or without research.
 *
 * @param rating       BUY | SELL (validated only) | NO_ACTIONABLE_SIGNAL | STALE_DATA | INSUFFICIENT_DATA | NOT_RATED
 * @param seriesStatus OK | DATA_QUALITY_WARNING | STALE_DATA | INSUFFICIENT_DATA
 */
public record QuantAssessment(String rating, String ruleOutput, boolean validated, Integer hitRatePct,
                              String signalSummary, String trend, String trendSummary,
                              List<String> forecastSummary, String calibrationSummary,
                              String seriesStatus, String priceDate, String summary) {}
