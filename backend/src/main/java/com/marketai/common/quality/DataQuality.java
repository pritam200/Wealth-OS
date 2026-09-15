package com.marketai.common.quality;

/**
 * How much verifiable data sits behind a computed number.
 *
 * This codebase already refuses to answer rather than guess in several places — AnalystService
 * returns an unscored assessment, ForecastService returns a no-forecast, RecommendationEngine
 * emits INSUFFICIENT_DATA instead of a HOLD that would be indistinguishable from a real
 * "nothing to do" verdict. That discipline was expressed as the bare string "INSUFFICIENT",
 * produced in five places and consumed in five others with nothing connecting them: a typo in
 * any one of them would have silently re-enabled the guessing those guards exist to prevent.
 *
 * The wire format is unchanged — {@link #wire()} emits exactly the strings the API already
 * returns, so existing clients and stored values keep working.
 */
public enum DataQuality {

    /** Every input the calculation wants is present. */
    FULL,

    /**
     * Usable, but something is missing and the caller should say so. Typically fewer than 200
     * daily bars, so no 200-DMA trend confirmation is available.
     */
    PARTIAL,

    /**
     * Not enough to compute an honest answer. Callers must decline rather than degrade —
     * emitting a confident-looking result from absent inputs is the failure mode this exists
     * to prevent.
     */
    INSUFFICIENT;

    /**
     * Parses a stored or wire value. Unknown and null values map to {@link #INSUFFICIENT}
     * deliberately: an unrecognised quality marker means we do not know what is behind the
     * number, and the safe reading of "I do not know" is "do not use it".
     */
    public static DataQuality of(String raw) {
        if (raw == null) return INSUFFICIENT;
        return switch (raw.trim().toUpperCase()) {
            case "FULL" -> FULL;
            case "PARTIAL" -> PARTIAL;
            default -> INSUFFICIENT;
        };
    }

    /** True when a result may be shown to the user at all. PARTIAL is usable *with* a caveat. */
    public boolean isUsable() {
        return this != INSUFFICIENT;
    }

    /** True when the caller must attach a caveat explaining what is missing. */
    public boolean needsCaveat() {
        return this == PARTIAL;
    }

    /** The exact string this value is stored and transmitted as. */
    public String wire() {
        return name();
    }
}
