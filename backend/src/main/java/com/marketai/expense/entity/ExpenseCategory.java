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
    FOOD_DELIVERY("Food Delivery"),
    GROCERIES("Groceries"),
    RESTAURANT_OUTING("Restaurant / Outing"),
    SHOPPING("Shopping"),
    TRAVEL("Travel"),
    FUEL("Fuel"),
    BILLS("Bills"),
    MEDICAL("Medical"),
    ENTERTAINMENT("Entertainment"),
    EMI("EMI"),
    UPI("UPI"),
    INVESTMENT("Investment"),
    // A credit-card bill payment (CRED, "pay CC bill" bank debits) moves money from a bank
    // account to a card issuer to settle a debt already spent against — booking it as spend
    // again here would double-count whatever purchases the card statement itself covers. Still
    // persisted (it's a real cash movement a user should be able to see), just excluded from
    // spend totals — see ExpenseRepository.sumByUserIdAndDateRange.
    ACCOUNT_TRANSFER("Account Transfer"),
    /** Bank and card charges: fees, late-payment charges, interest charged. Real spending. */
    BANK_CHARGES("Bank Charges"),
    UNCATEGORIZED("Uncategorized");

    private final String label;

    ExpenseCategory(String label) { this.label = label; }

    public String getLabel() { return label; }

    /**
     * Whether money booked under this category is spending. A card-bill payment or own-account
     * transfer moves money already spent or still the user's; an investment is booked from the
     * fund or broker confirmation. Counting either as spend double-counts it, so every spend
     * total uses this one rule.
     */
    public boolean isSpending() { return this != ACCOUNT_TRANSFER && this != INVESTMENT; }

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
