package com.marketai.signal.service;

/**
 * Bar sizes the signal engine reasons over.
 *
 * H4 has no native Yahoo interval and is resampled locally from H1 — requesting "4h" returns
 * an error rather than data, which would otherwise look like "no history available".
 */
public enum Timeframe {
    M15("15m", "60d",  true),
    H1 ("60m", "730d", true),
    H4 ("4h",  null,   false),   // derived from H1
    D1 ("1d",  "2y",   true);

    private final String code;
    /** Longest window Yahoo will serve at this interval; null when not directly fetchable. */
    private final String maxRange;
    private final boolean fetchable;

    Timeframe(String code, String maxRange, boolean fetchable) {
        this.code = code; this.maxRange = maxRange; this.fetchable = fetchable;
    }

    public String code()      { return code; }
    public String maxRange()  { return maxRange; }
    public boolean fetchable(){ return fetchable; }

    /** Minimum bars needed before indicators on this timeframe mean anything. */
    public int minBarsForSignal() {
        return this == D1 ? 50 : 30;
    }
}
