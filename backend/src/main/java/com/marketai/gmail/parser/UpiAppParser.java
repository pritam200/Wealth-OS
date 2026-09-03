package com.marketai.gmail.parser;

import com.marketai.expense.entity.ExpenseCategory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Paytm / PhonePe / Google Pay send their own transaction-confirmation emails
 * (from the app's own domain, not the bank) with different phrasing than a bank
 * SMS-style alert — "You paid ₹250 to X", "₹500 sent", "You received ₹1,000" —
 * which BankTransactionParser's CREDIT_WORDS/DEBIT_WORDS don't reliably catch.
 */
@Component
@Order(1)
public class UpiAppParser implements EmailParser {

    private static final Pattern AMOUNT = Pattern.compile(
        "(?:Rs\\.?\\s*|INR\\s*|₹\\s*)([\\d,]+\\.?\\d{0,2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile(
        "(?:on|dated)\\s+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{2,4})",
        Pattern.CASE_INSENSITIVE);

    private static final String[] SENT_WORDS     = { "you paid", "you have paid", "payment of", "sent", "money sent", "payment successful" };
    private static final String[] RECEIVED_WORDS = { "you received", "money received", "received from", "credited" };

    @Override
    public boolean canParse(String from, String subject) {
        boolean fromApp = ParserUtil.containsIgnoreCase(from, "paytm", "phonepe", "googlepay", "gpay");
        boolean txn = ParserUtil.containsIgnoreCase(subject, "payment", "paid", "sent", "received", "transaction", "upi");
        return fromApp && txn;
    }

    @Override
    public List<ParsedEmail> parse(String from, String subject, String bodyText) {
        List<ParsedEmail> out = new ArrayList<>();
        String text = (subject == null ? "" : subject) + "\n" + (bodyText == null ? "" : bodyText);

        BigDecimal amount = ParserUtil.parseMoney(ParserUtil.findFirst(text, AMOUNT));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return out;
        LocalDate date = ParserUtil.parseDate(ParserUtil.findFirst(text, DATE));
        if (date == null) date = LocalDate.now();

        boolean sent = ParserUtil.containsIgnoreCase(text, SENT_WORDS);
        boolean received = ParserUtil.containsIgnoreCase(text, RECEIVED_WORDS);
        String appName = ParserUtil.containsIgnoreCase(from, "paytm") ? "Paytm"
            : ParserUtil.containsIgnoreCase(from, "phonepe") ? "PhonePe" : "Google Pay";

        if (received && !sent) {
            String sender = SpendCategorizer.extractMerchant(text);
            out.add(ParsedEmail.builder()
                .type(ParsedEmail.Type.INCOME)
                .incomeSource("Other")
                .merchant(sender)
                .paymentMethod(appName + " UPI")
                .amount(amount)
                .tradeDate(date)
                .sourceDescription(String.format("Received via %s: ₹%.0f%s", appName, amount,
                    sender != null ? " from " + sender : ""))
                .build());
        } else if (sent) {
            String merchant = SpendCategorizer.extractMerchant(text);
            ExpenseCategory category = SpendCategorizer.categorize(text);
            if (category == ExpenseCategory.INVESTMENT) return out;
            String desc = merchant != null ? merchant : category.getLabel() + " spend";
            out.add(ParsedEmail.builder()
                .type(ParsedEmail.Type.EXPENSE)
                .category(category.getLabel())
                .merchant(merchant)
                .paymentMethod(appName + " UPI")
                .amount(amount)
                .tradeDate(date)
                .sourceDescription(String.format("%s via %s: ₹%.0f", desc, appName, amount))
                .build());
        }
        return out;
    }
}
