package com.marketai.gmail.parser;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ParsedEmail {
    public enum Type {
        TRADE_BUY, TRADE_SELL, FD_OPEN, RD_OPEN, MF_SIP, MF_REDEEM, DIVIDEND,
        INCOME, EXPENSE, CARD_BILL, UNKNOWN
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

    // Human-readable description for UI
    private String sourceDescription;
}
