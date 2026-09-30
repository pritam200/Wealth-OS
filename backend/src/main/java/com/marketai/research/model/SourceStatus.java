package com.marketai.research.model;

/**
 * Whether a research source answered. Recorded with every research result so a gap ("NSE
 * filings unavailable") is visible instead of looking like "nothing happened".
 *
 * @param status OK | UNAVAILABLE | NOT_SUPPORTED | SKIPPED
 */
public record SourceStatus(String source, String status, int items, String detail) {
    public static SourceStatus ok(String source, int items) { return new SourceStatus(source, "OK", items, null); }
    public static SourceStatus unavailable(String source, String detail) { return new SourceStatus(source, "UNAVAILABLE", 0, detail); }
    public static SourceStatus notSupported(String source, String detail) { return new SourceStatus(source, "NOT_SUPPORTED", 0, detail); }
}
