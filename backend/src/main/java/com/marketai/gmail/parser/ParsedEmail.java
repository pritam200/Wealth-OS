package com.marketai.gmail.parser;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder(toBuilder = true) @NoArgsConstructor @AllArgsConstructor
public class ParsedEmail {
    public enum Type {
        TRADE_BUY, TRADE_SELL, FD_OPEN, RD_OPEN, MF_SIP, MF_REDEEM, DIVIDEND,
        INCOME, EXPENSE, CARD_BILL, CARD_PAYMENT,
        /** Split, bonus, merger or demerger — see {@link #corporateAction}. */
        CORPORATE_ACTION,
        /** Interest paid out on a deposit (a payout FD), with any TDS deducted from it. */
        DEPOSIT_INTEREST,
        /** A deposit matured or was closed early; {@link #amount} is what was paid out. */
        DEPOSIT_CLOSE,
        /** Money back on an earlier purchase, or a failed debit reversed. */
        REFUND,
        /** A transfer between the user's own accounts, or a cash withdrawal. */
        OWN_TRANSFER,
        UNKNOWN
    }

    private Type type;

    // Trade (stock buy/sell)
    private String symbol;
    private String exchange;    // NSE / BSE
    private Integer quantity;
    private BigDecimal price;
    private LocalDate tradeDate;

    // Income / Expense / Card
    private String incomeSource;   // Salary | Interest | Dividend | Other
    private String category;       // expense category
    private String merchant;       // payee / merchant
    private String paymentMethod;  // e.g. "HDFC Bank", "Paytm UPI", "Credit Card ****1234"
    private String cardLast4;      // credit card last 4 digits
    private LocalDate dueDate;     // card bill due date
    private LocalDate statementDate;   // card bill statement/generation date
    private LocalDate paymentDate;     // card payment confirmation date (CARD_PAYMENT)
    private String paymentReference;   // bank/UPI reference/RRN quoted in a payment confirmation
    private String paymentStatus;      // "CONFIRMED" | "REVERSED" — set by CardPaymentParser
    private BigDecimal previousBalance; // card bill: previous cycle's closing balance, when stated
    private BigDecimal minimumDue;      // card bill: minimum amount due, when stated
    private BigDecimal cycleDebits;     // card bill: "purchases/debits this cycle", when stated
    private BigDecimal cycleCredits;    // card bill: "payments/credits this cycle", when stated

    // FD
    private String bank;
    private BigDecimal principal;
    private BigDecimal rate;
    private LocalDate startDate;
    private LocalDate maturityDate;
    private String compounding;

    // RD
    private BigDecimal monthlyAmount;
    private Integer tenureMonths;

    // MF
    private String fundName;
    private BigDecimal nav;
    private BigDecimal units;
    private BigDecimal amount;
    private String folio;       // MF folio number
    private String provider;    // AMC name: SBI, HDFC, ICICI Pru, Nippon, etc.

    // Asset identifiers (MF and/or EQUITY) — null unless the source clearly states them
    private String isin;        // ISIN, MF scheme or listed equity
    private String dpId;        // demat Depository Participant id (EQUITY)
    private String clientId;    // broker client id (EQUITY)

    // Human-readable description for UI
    private String sourceDescription;

    // 0 for the first line in a document with this exact content, 1 for an identical second
    // line, etc. A single statement never lists the same transaction twice, so two identical
    // lines are two real transactions and must not dedup against each other.
    @Builder.Default
    private int occurrenceInSource = 0;

    // True only when a person accepted this item from the review queue. It lifts the "looks like
    // one already recorded from another email" holds — the person has said it is separate — but
    // never the exact-reference/fingerprint gates, which identify the same transaction outright.
    @Builder.Default
    private boolean userConfirmed = false;

    // Corporate actions: SPLIT | BONUS | MERGER | DEMERGER. A ratio "from:to" — a 1:5 split
    // turns 1 share into 5; a 1:1 bonus gives 1 new share per 1 held; a merger gives "to" shares
    // of newSymbol for every "from" held.
    private String corporateAction;
    private BigDecimal ratioFrom;
    private BigDecimal ratioTo;
    private String newSymbol;

    /** Brokerage, STT, GST, stamp duty and exchange charges on a trade, in total. */
    private BigDecimal charges;
    /** The broker's trade or order number, the bank's UTR — the issuer's own reference. */
    private String tradeReference;
    /** Shared by the legs of one event (a fund switch's redemption and purchase). */
    private String linkGroup;
    /** Tax deducted at source from interest or a dividend. */
    private BigDecimal tds;
    /** An IDCW distribution reinvested in the fund: bought units, and taxable dividend income. */
    @Builder.Default
    private boolean idcwReinvest = false;
    /** DEPOSIT_INTEREST / DEPOSIT_CLOSE: "FD" or "RD". */
    private String instrumentKind;
    /** OWN_TRANSFER: true for money arriving, false for money leaving. */
    private Boolean incoming;

    // Source-document details, carried to the import log for "View source".
    private String sourceAttachmentId;
    private String sourceDocumentHash;
    private String extractionMethod;
    private Double extractionConfidence;
    /** Provider, model and prompt version that read it ("gemini:gemini-2.5-flash/transaction-extraction-v1"). */
    private String extractionVersion;
    /** When the read that produced this began — lines booked before it came from an earlier read. */
    private java.time.LocalDateTime readStartedAt;
}
