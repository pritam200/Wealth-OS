package com.marketai.research.service;

import com.marketai.research.model.Evidence;
import com.marketai.research.model.Fact;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps model-written text from carrying figures that code did not produce. Every number in a
 * sentence must match (within rounding) a figure in the verified facts or the evidence text;
 * anything else — a target, a probability, a computed difference — is replaced with a marker
 * and recorded, so a figure on screen is always one the data contains.
 *
 * <p>Not checked: years, indicator periods (RSI 14, SMA 200 …), textbook thresholds (RSI 30/70,
 * ADX 25), small counts, dates, and the range levels 50/90/95%.
 */
public final class NumberGuard {

    public static final String MARKER = "[figure not in verified data]";

    /** Periods that name an indicator or horizon rather than state a value. */
    static final Set<Integer> PERIODS = Set.of(1, 5, 9, 12, 14, 20, 26, 50, 60, 100, 120, 200, 250, 252);
    /** Textbook indicator thresholds ("RSI below 30", "ADX above 25") — conventions, not readings. */
    static final Set<Integer> THRESHOLDS = Set.of(25, 30, 70, 80);

    private static final Pattern DATE = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}(?:T[\\d:.]+)?\\b"
            + "|\\b\\d{1,2}[-/ ](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*[-/ ,]+\\d{4}\\b"
            + "|\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]* \\d{1,2},? \\d{4}\\b"
            + "|\\bQ[1-4] ?FY ?\\d{2,4}\\b|\\bFY ?\\d{2,4}\\b|\\bH[12] ?FY ?\\d{2,4}\\b|\\bQ[1-4]\\b|\\b\\d{1,2}:\\d{2}\\b",
            Pattern.CASE_INSENSITIVE);

    /** A figure: optional sign and ₹, digits with Indian or Western grouping, optional decimals and scale. */
    static final Pattern FIGURE = Pattern.compile(
            "(?<![A-Za-z0-9_.])([-+−]?)(₹|Rs\\.? ?|INR ?)?(\\d(?:[\\d,]*\\d)?(?:\\.\\d+)?)"
            + "(\\s?(?:%|×|x\\b|lakh crore|lakh|lakhs|crore|crores|cr\\b|bn\\b|billion|million|mn\\b|trillion|k\\b|bps|basis points))?",
            Pattern.CASE_INSENSITIVE);

    private final List<Double> allowed;
    private final List<String> removed = new ArrayList<>();

    public NumberGuard(List<Fact> facts, List<Evidence> evidence) {
        // The forecast's range levels (50% and 90% ranges, 95% intervals) name a range, not a result.
        List<Double> a = new ArrayList<>(List.of(50.0, 90.0, 95.0));
        for (Fact f : facts) {
            if (f.numeric() != null) a.add(Math.abs(f.numeric()));
            if (f.value() != null) a.addAll(figures(f.value()));
            if (f.asOf() != null) a.addAll(figures(f.asOf()));
        }
        for (Evidence e : evidence) {
            if (e.title() != null) a.addAll(figures(e.title()));
            if (e.detail() != null) a.addAll(figures(e.detail()));
        }
        this.allowed = a;
    }

    /** The text with unverified figures replaced by {@link #MARKER}. */
    public String clean(String text) {
        if (text == null || text.isBlank()) return text;
        String masked = mask(text);
        Matcher m = FIGURE.matcher(masked);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            Double v = value(m);
            if (v == null || exempt(m, v) || matches(v)) continue;
            out.append(text, last, m.start());
            out.append(MARKER);
            removed.add(text.substring(m.start(), m.end()).trim());
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    public List<String> removed() { return List.copyOf(removed); }

    /** Dates are blanked (same length, so offsets still line up with the original text). */
    private static String mask(String text) {
        Matcher d = DATE.matcher(text);
        StringBuilder sb = new StringBuilder(text);
        while (d.find()) for (int i = d.start(); i < d.end(); i++) sb.setCharAt(i, '#');
        return sb.toString();
    }

    private static boolean exempt(Matcher m, double v) {
        String unit = m.group(4) == null ? "" : m.group(4).trim();
        boolean plain = unit.isEmpty() && m.group(2) == null && !m.group(3).contains(".") && !m.group(3).contains(",");
        if (!plain) return false;
        int n = (int) v;
        return (n >= 1990 && n <= 2100) || PERIODS.contains(n) || THRESHOLDS.contains(n) || n <= 10;
    }

    private boolean matches(double v) {
        for (double a : allowed) {
            if (close(v, a)) return true;
        }
        return false;
    }

    /** Within rounding: 0.5% relative, or the last shown digit of a small number. */
    static boolean close(double v, double a) {
        double diff = Math.abs(v - a);
        return diff <= 0.005 * Math.max(Math.abs(a), Math.abs(v)) || diff <= 0.051 && Math.abs(a) < 10;
    }

    static List<Double> figures(String s) {
        List<Double> out = new ArrayList<>();
        Matcher m = FIGURE.matcher(mask(s));
        while (m.find()) {
            Double v = value(m);
            if (v != null) out.add(v);
        }
        return out;
    }

    static Double value(Matcher m) {
        try {
            double v = Double.parseDouble(m.group(3).replace(",", ""));
            String unit = m.group(4) == null ? "" : m.group(4).trim().toLowerCase(Locale.ROOT);
            double mult = switch (unit) {
                case "lakh crore" -> 1e12;
                case "crore", "crores", "cr" -> 1e7;
                case "lakh", "lakhs" -> 1e5;
                case "trillion" -> 1e12;
                case "bn", "billion" -> 1e9;
                case "million", "mn" -> 1e6;
                case "k" -> 1e3;
                default -> 1;
            };
            return Math.abs(v * mult);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
