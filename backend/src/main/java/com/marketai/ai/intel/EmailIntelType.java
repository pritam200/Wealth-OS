package com.marketai.ai.intel;

import com.marketai.gmail.parser.ParsedEmail;

/**
 * Semantic classification of a financial email — finer-grained than {@link ParsedEmail.Type},
 * which is the set of things the importer can actually book.
 *
 * The distinction matters: "UPI_EXPENSE" and "CARD_EXPENSE" both import as an EXPENSE, but
 * keeping them apart lets the review queue show a human what the model actually believed, and
 * lets analytics separate payment channels later without re-parsing anything.
 *
 * INTERNAL_TRANSFER is the important one: it maps to no importable type on purpose. A
 * bank→MF movement is not income, not an expense, and not a new investment on the bank side —
 * booking it as any of those corrupts net worth.
 */
public enum EmailIntelType {

    /* ── Spending ── */
    UPI_EXPENSE(ParsedEmail.Type.EXPENSE),
    CARD_EXPENSE(ParsedEmail.Type.EXPENSE),
    NETBANKING_EXPENSE(ParsedEmail.Type.EXPENSE),
    ATM_WITHDRAWAL(ParsedEmail.Type.EXPENSE),
    AUTO_DEBIT_EXPENSE(ParsedEmail.Type.EXPENSE),
    EMI_PAYMENT(ParsedEmail.Type.EXPENSE),
    BILL_PAYMENT(ParsedEmail.Type.EXPENSE),

    /* ── Cards ── */
    CARD_BILL_GENERATED(ParsedEmail.Type.CARD_BILL),
    CARD_BILL_PAID(ParsedEmail.Type.EXPENSE),

    /* ── Equity ── */
    STOCK_BUY(ParsedEmail.Type.TRADE_BUY),
    STOCK_SELL(ParsedEmail.Type.TRADE_SELL),
    DIVIDEND(ParsedEmail.Type.DIVIDEND),

    /* ── Mutual funds ── */
    MF_SIP(ParsedEmail.Type.MF_SIP),
    MF_LUMPSUM(ParsedEmail.Type.MF_SIP),
    MF_REDEMPTION(ParsedEmail.Type.MF_REDEEM),
    MF_SWITCH(null),          // two legs in one email — needs review, never auto-booked

    /* ── Deposits ── */
    FD_OPEN(ParsedEmail.Type.FD_OPEN),
    FD_MATURITY(null),        // handled by the FD lifecycle, not as a fresh record
    RD_OPEN(ParsedEmail.Type.RD_OPEN),
    RD_INSTALLMENT(null),     // an RD debit is already implied by the RD schedule

    /* ── Income ── */
    SALARY(ParsedEmail.Type.INCOME),
    INTEREST_CREDIT(ParsedEmail.Type.INCOME),
    REFUND(ParsedEmail.Type.INCOME),
    RENTAL_INCOME(ParsedEmail.Type.INCOME),

    /* ── Movements that must never change net worth ── */
    INTERNAL_TRANSFER(null),
    SELF_TRANSFER(null),

    /* ── Not a transaction ── */
    OTP_OR_ALERT(null),
    PROMOTIONAL(null),
    STATEMENT_ONLY(null),
    UNKNOWN(null);

    private final ParsedEmail.Type importAs;

    EmailIntelType(ParsedEmail.Type importAs) { this.importAs = importAs; }

    /** The bookable type, or null when this classification must not be auto-imported. */
    public ParsedEmail.Type importAs() { return importAs; }

    public boolean isImportable() { return importAs != null; }

    /** True for classifications that shift money between the user's own accounts. */
    public boolean isTransfer() { return this == INTERNAL_TRANSFER || this == SELF_TRANSFER; }

    public static EmailIntelType fromLabel(String s) {
        if (s == null) return UNKNOWN;
        for (EmailIntelType t : values()) {
            if (t.name().equalsIgnoreCase(s.trim())) return t;
        }
        return UNKNOWN;
    }
}
