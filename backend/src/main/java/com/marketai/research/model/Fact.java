package com.marketai.research.model;

/**
 * One verified input to research — a number or reading the backend computed or fetched, with
 * where it came from and when. The LLM may cite a fact by {@link #id()}; it may never state a
 * figure that is not one of these.
 *
 * @param id       "F12" — stable within one research context
 * @param category MARKET | TECHNICAL | FORECAST | SIGNAL | FUNDAMENTAL | COMPANY | SECTOR | MACRO | PORTFOLIO | DATA_QUALITY | FUND
 * @param basis    how the value was produced: DATA (fetched), CALCULATION (derived by code),
 *                 MODEL (statistical model output), PORTFOLIO (the user's own records)
 * @param value    display value; null when unavailable
 * @param numeric  the number behind {@code value}, for checking figures the LLM repeats; null if none
 */
public record Fact(String id, String category, String basis, String label, String value, Double numeric,
                   String unit, String asOf, String source, boolean available) {

    public static Fact of(String category, String basis, String label, Double numeric, String unit, String asOf, String source) {
        return new Fact(null, category, basis, label, numeric == null ? null : Numbers.display(numeric, unit), numeric, unit, asOf, source, numeric != null);
    }

    public static Fact text(String category, String basis, String label, String value, String asOf, String source) {
        return new Fact(null, category, basis, label, value, null, null, asOf, source, value != null && !value.isBlank());
    }

    public static Fact missing(String category, String label, String why) {
        return new Fact(null, category, "DATA", label, null, null, null, null, why, false);
    }

    public Fact withId(String newId) {
        return new Fact(newId, category, basis, label, value, numeric, unit, asOf, source, available);
    }
}
