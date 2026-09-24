package com.marketai.gmail.parser;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder(toBuilder = true) @NoArgsConstructor @AllArgsConstructor
public class ParsedEmail {
    public enum Type {
        TRADE_BUY, TRADE_SELL, FD_OPEN, RD_OPEN, MF_SIP, MF_REDEEM, DIVIDEND,
        INCOME, EXPENSE, CARD_BILL, CARD_PAYMENT, UNKNOWN
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
}
