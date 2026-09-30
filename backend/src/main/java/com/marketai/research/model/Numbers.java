package com.marketai.research.model;

import java.util.Locale;

/** Display formatting for facts — the same text is shown to the model and to the user. */
public final class Numbers {
    private Numbers() {}

    public static String display(double v, String unit) {
        String n;
        double a = Math.abs(v);
        if (a >= 1e7 && "₹".equals(unit)) n = String.format(Locale.ROOT, "%.2f crore", v / 1e7);
        else if (a >= 1000) n = String.format(Locale.ROOT, "%.2f", v);
        else if (a >= 1) n = String.format(Locale.ROOT, "%.2f", v);
        else n = String.format(Locale.ROOT, "%.4f", v);
        if (unit == null || unit.isBlank()) return n;
        return switch (unit) {
            case "₹" -> "₹" + n;
            case "%" -> n + "%";
            default -> n + " " + unit;
        };
    }
}
