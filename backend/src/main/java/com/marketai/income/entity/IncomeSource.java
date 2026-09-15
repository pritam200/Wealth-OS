package com.marketai.income.entity;

/**
 * Canonical income sources. Mirrors {@link com.marketai.expense.entity.ExpenseCategory} —
 * previously {@code Income.source} was a free-text String, so the same real-world source
 * could be stored as "Dividend"/"dividend"/"Divident" and silently split analytics totals.
 * CAPITAL_GAIN and INTEREST exist because PortfolioService/TrackingService book realized
 * gains and FD/RD interest as Income rows.
 */
public enum IncomeSource {
    SALARY("Salary"),
    BONUS("Bonus"),
    FREELANCE("Freelance"),
    BUSINESS("Business"),
    DIVIDEND("Dividend"),
    INTEREST("Interest"),
    CAPITAL_GAIN("Capital Gain"),
    RENTAL("Rental"),
    OTHER("Other");

    private final String label;

    IncomeSource(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** Looks up by display label or enum name (case-insensitive), mapping known legacy
     *  free-text variants and falling back to OTHER for anything unrecognised. */
    public static IncomeSource fromLabel(String label) {
        if (label == null) return OTHER;
        for (IncomeSource s : values()) {
            if (s.label.equalsIgnoreCase(label) || s.name().equalsIgnoreCase(label)) return s;
        }
        switch (label.toLowerCase().trim()) {
            case "capital loss":   return CAPITAL_GAIN; // a signed amount on the same source
            case "dividends":      return DIVIDEND;
            case "fd interest":
            case "rd interest":
            case "savings interest": return INTEREST;
            case "rent":           return RENTAL;
            case "consulting":     return FREELANCE;
            default:               return OTHER;
        }
    }
}
