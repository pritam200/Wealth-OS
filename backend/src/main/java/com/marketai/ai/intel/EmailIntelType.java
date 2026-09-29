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
    OTHER_EXPENSE(ParsedEmail.Type.EXPENSE),

    /* ── Cards ── */
    CARD_BILL_GENERATED(ParsedEmail.Type.CARD_BILL),
    // Paying the card bill settles purchases that are already booked as spend, one by one.
    // Booking it as an expense counted that spend twice; it is a card payment.
    CARD_BILL_PAID(ParsedEmail.Type.CARD_PAYMENT),

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
    // Closes the open deposit it belongs to (FD lifecycle), never a fresh record.
    FD_MATURITY(ParsedEmail.Type.DEPOSIT_CLOSE),
    RD_OPEN(ParsedEmail.Type.RD_OPEN),
    RD_INSTALLMENT(null),     // an RD debit is already implied by the RD schedule

    /* ── Income ── */
    SALARY(ParsedEmail.Type.INCOME),
    INTEREST_CREDIT(ParsedEmail.Type.INCOME),
    // A refund reverses spending; it is not income. It is booked against the purchase it
    // reverses (or held for review when that purchase can't be found).
    REFUND(ParsedEmail.Type.REFUND),
    RENTAL_INCOME(ParsedEmail.Type.INCOME),
    OTHER_INCOME(ParsedEmail.Type.INCOME),

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

    /** The closest classification for a record the importer already built — so a held-back
     *  item shows what it would book as, instead of UNKNOWN. */
    public static EmailIntelType forParsed(ParsedEmail pe) {
        if (pe == null || pe.getType() == null) return UNKNOWN;
        return switch (pe.getType()) {
            case TRADE_BUY -> STOCK_BUY;
            case TRADE_SELL -> STOCK_SELL;
            case MF_SIP -> MF_SIP;
            case MF_REDEEM -> MF_REDEMPTION;
            case FD_OPEN -> FD_OPEN;
            case RD_OPEN -> RD_OPEN;
            case DIVIDEND -> DIVIDEND;
            case CARD_BILL -> CARD_BILL_GENERATED;
            case CARD_PAYMENT -> CARD_BILL_PAID;
            case EXPENSE -> OTHER_EXPENSE;
            case REFUND -> REFUND;
            case DEPOSIT_CLOSE -> FD_MATURITY;
            case DEPOSIT_INTEREST -> INTEREST_CREDIT;
            case OWN_TRANSFER -> SELF_TRANSFER;
            case CORPORATE_ACTION -> STATEMENT_ONLY;
            case INCOME -> {
                String src = pe.getIncomeSource() == null ? "" : pe.getIncomeSource().trim().toLowerCase();
                yield switch (src) {
                    case "salary" -> SALARY;
                    case "interest" -> INTEREST_CREDIT;
                    case "rental" -> RENTAL_INCOME;
                    default -> OTHER_INCOME;
                };
            }
            case UNKNOWN -> UNKNOWN;
        };
    }

    public static EmailIntelType fromLabel(String s) {
        if (s == null) return UNKNOWN;
        for (EmailIntelType t : values()) {
            if (t.name().equalsIgnoreCase(s.trim())) return t;
        }
        return UNKNOWN;
    }
}
