package com.marketai.expense.entity;

/**
 * Canonical expense categories. Replaces the previous free-text `category` string
 * to stop drift/typos as more banks/merchants are added to the Gmail parsers.
 * INVESTMENT exists only so {@code SpendCategorizer} can flag investment-flavoured
 * debits — it must never actually be persisted on an {@link Expense} row (those are
 * filtered out before reaching the Expense table; see BankTransactionParser).
 */
public enum ExpenseCategory {
    FOOD("Food"),
    SHOPPING("Shopping"),
    TRAVEL("Travel"),
    FUEL("Fuel"),
    BILLS("Bills"),
    MEDICAL("Medical"),
    ENTERTAINMENT("Entertainment"),
    EMI("EMI"),
    UPI("UPI"),
    INVESTMENT("Investment"),
    UNCATEGORIZED("Uncategorized");

    private final String label;

    ExpenseCategory(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** Looks up by display label (case-insensitive), falling back to UNCATEGORIZED
     *  for legacy free-text values (e.g. "Utilities", "Health", "Other") that predate this enum. */
    public static ExpenseCategory fromLabel(String label) {
        if (label == null) return UNCATEGORIZED;
        for (ExpenseCategory c : values()) {
            if (c.label.equalsIgnoreCase(label) || c.name().equalsIgnoreCase(label)) return c;
        }
        switch (label.toLowerCase()) {
            case "utilities": return BILLS;
            case "health": return MEDICAL;
            case "other": return UNCATEGORIZED;
            default: return UNCATEGORIZED;
        }
    }
}
