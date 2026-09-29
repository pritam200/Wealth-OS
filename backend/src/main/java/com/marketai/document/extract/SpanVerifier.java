package com.marketai.document.extract;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that every extracted field's source span genuinely occurs in the document.
 *
 * <p>This is the grounding check. Schema-constrained decoding guarantees an LLM returns
 * well-formed JSON; it guarantees nothing about whether the values were read from the document
 * or invented in a plausible shape. Verifying the span mechanically closes that gap without
 * trusting anything the model says about itself.
 *
 * <p>Whitespace is normalised before comparison because PDF text extraction routinely
 * introduces line breaks and runs of spaces inside a value that is visually contiguous.
 * Nothing else is relaxed: the characters must be present, in order, in the source.
 */
public final class SpanVerifier {

    private SpanVerifier() {}

    public record Result(boolean allFound, List<String> unfoundFields, int checked) {
        public double score() {
            if (checked == 0) return 0.0;
            return (double) (checked - unfoundFields.size()) / checked;
        }
    }

    /** Collapses runs of whitespace so extraction line-wrapping does not cause false negatives. */
    static String normalise(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Whether {@code span} appears verbatim in {@code sourceText} (whitespace-insensitive). */
    public static boolean appearsIn(String sourceText, String span) {
        return span != null && !span.isBlank() && normalise(sourceText).contains(normalise(span));
    }

    public static Result verify(String sourceText, List<ExtractedField> fields) {
        if (fields == null || fields.isEmpty()) {
            // No fields is not the same as all fields verified. A caller that extracted nothing
            // has nothing grounded, and scoring it 1.0 would read as perfect confidence.
            return new Result(false, List.of(), 0);
        }
        String haystack = normalise(sourceText);
        List<String> unfound = new ArrayList<>();

        for (ExtractedField f : fields) {
            if (!haystack.contains(normalise(f.sourceSpan()))) {
                unfound.add(f.name());
            }
        }
        return new Result(unfound.isEmpty(), List.copyOf(unfound), fields.size());
    }

    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    /**
     * Whether {@code amount} is one of the figures written in {@code span} ("₹1,23,456.00",
     * "Rs 250", "INR 499.98" all count). Verifying that the cited line exists is not enough on
     * its own: the model could quote a real line and still report a different figure.
     */
    public static boolean containsAmount(String span, java.math.BigDecimal amount) {
        if (span == null || amount == null) return false;
        java.util.regex.Matcher m = NUMBER.matcher(span);
        while (m.find()) {
            String digits = m.group().replace(",", "");
            try {
                if (new java.math.BigDecimal(digits).compareTo(amount.abs()) == 0) return true;
            } catch (NumberFormatException ignored) {
                // not a number after all
            }
        }
        return false;
    }

    private static final String MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec";
    // A year-less "10.09" is as likely an amount as a date, so a dot separator needs the year.
    private static final java.util.regex.Pattern DAY_MONTH_YEAR = java.util.regex.Pattern.compile(
        "(?<![\\d.])(\\d{1,2})(?:[-/.](\\d{1,2})[-/.](\\d{2,4})|[-/](\\d{1,2}))(?![\\d.])");
    private static final java.util.regex.Pattern ISO = java.util.regex.Pattern.compile(
        "(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)");
    private static final java.util.regex.Pattern DAY_MON = java.util.regex.Pattern.compile(
        "(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[\\s\\-/.,]*(" + MONTHS + ")[a-z]*\\.?(?:[\\s\\-/.,']*(\\d{2,4}))?(?!\\d)");
    private static final java.util.regex.Pattern MON_DAY = java.util.regex.Pattern.compile(
        "(" + MONTHS + ")[a-z]*\\.?[\\s\\-/]*(\\d{1,2})(?:st|nd|rd|th)?(?:[\\s,]*(\\d{4}))?(?!\\d)");

    /**
     * Whether {@code span} states {@code date} — "10-09-2026", "10/09/26", "2026-09-10",
     * "10 Sep 2026", "Sep 10, 2026", or a year-less "10-Sep" as statement tables print it
     * (the year then comes from the statement period). A numeric date is accepted in either
     * day/month order, since the document's convention isn't known here.
     */
    public static boolean containsDate(String span, java.time.LocalDate date) {
        if (span == null || date == null) return false;
        String text = span.toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher m = ISO.matcher(text);
        while (m.find()) {
            if (matches(date, i(m.group(3)), i(m.group(2)), m.group(1))) return true;
        }
        m = DAY_MONTH_YEAR.matcher(text);
        while (m.find()) {
            int a = i(m.group(1)), b = i(m.group(2) != null ? m.group(2) : m.group(4));
            if (matches(date, a, b, m.group(3)) || matches(date, b, a, m.group(3))) return true;
        }
        m = DAY_MON.matcher(text);
        while (m.find()) {
            if (matches(date, i(m.group(1)), monthOf(m.group(2)), m.group(3))) return true;
        }
        m = MON_DAY.matcher(text);
        while (m.find()) {
            if (matches(date, i(m.group(2)), monthOf(m.group(1)), m.group(3))) return true;
        }
        return false;
    }

    private static boolean matches(java.time.LocalDate date, int day, int month, String year) {
        if (day != date.getDayOfMonth() || month != date.getMonthValue()) return false;
        if (year == null || year.isEmpty()) return true;
        int y = i(year);
        if (year.length() == 2) y += 2000;
        return y == date.getYear();
    }

    private static int monthOf(String mon) {
        return MONTHS.indexOf(mon.substring(0, 3)) / 4 + 1;
    }

    private static int i(String s) {
        return Integer.parseInt(s);
    }
}
