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
    /**
     * Gross proceeds of a sale that could not be matched to a tracked holding, so no cost basis
     * is known. Deliberately NOT {@link #CAPITAL_GAIN}: a gain is proceeds minus a basis, and
     * booking ₹1,50,000 of proceeds as ₹1,50,000 of "gain" put tens of thousands of rupees of
     * phantom tax into the FY estimate. {@code TaxService} reads named buckets only, so this one
     * stays out of the estimate until the user reconciles it against a real position.
     */
    UNMATCHED_SALE("Unmatched Sale"),
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
