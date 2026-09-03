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
 * Parses bank & wallet transaction alerts (money in / money out) and routes them:
 *   - credits (salary / interest / refund)  → INCOME
 *   - debits  (spends / withdrawals / bills) → EXPENSE (auto-categorised)
 * Deliberately ignores FD/RD/deposit mails (handled by the bank FD parsers).
 * Lowest priority (@Order(100)): this is the generic catch-all, so every more
 * specific parser (RTA/broker/card/dividend/bank-FD) must get first refusal.
 */
@Component
@Order(100)
public class BankTransactionParser implements EmailParser {

    private static final Pattern AMOUNT = Pattern.compile(
        "(?:Rs\\.?\\s*|INR\\s*|₹\\s*)([\\d,]+\\.?\\d{0,2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile(
        "(?:on|dated)\\s+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{2,4})",
        Pattern.CASE_INSENSITIVE);

    private static final String[] BANKS = {
        "hdfc", "sbi", "icici", "kotak", "axis", "yesbank", "idfc", "indusind",
        "bank", "paytm", "phonepe", "gpay", "googlepay", "amazonpay", "upi"
    };
    private static final String[] CREDIT_WORDS = { "credited", "received", "deposited", "credit of", "has been credited" };
    private static final String[] DEBIT_WORDS  = { "debited", "spent", "withdrawn", "paid", "payment of", "debit of", "has been debited" };
    private static final String[] SKIP_WORDS   = { "fixed deposit", "recurring deposit", " fd ", " rd ", "otp", "statement is ready", "e-statement" };

    @Override
    public boolean canParse(String from, String subject) {
        String s = (from == null ? "" : from) + " " + (subject == null ? "" : subject);
        boolean bank = ParserUtil.containsIgnoreCase(from, BANKS) || ParserUtil.containsIgnoreCase(subject, BANKS);
        boolean txn  = ParserUtil.containsIgnoreCase(s, CREDIT_WORDS) || ParserUtil.containsIgnoreCase(s, DEBIT_WORDS)
                    || ParserUtil.containsIgnoreCase(subject, "transaction alert", "txn", "spent", "debit", "credit");
        boolean skip = ParserUtil.containsIgnoreCase(subject, SKIP_WORDS);
        return bank && txn && !skip;
    }

    @Override
    public List<ParsedEmail> parse(String from, String subject, String bodyText) {
        List<ParsedEmail> out = new ArrayList<>();
        String text = (subject == null ? "" : subject) + "\n" + (bodyText == null ? "" : bodyText);
        if (ParserUtil.containsIgnoreCase(text, SKIP_WORDS)) return out;

        BigDecimal amount = ParserUtil.parseMoney(ParserUtil.findFirst(text, AMOUNT));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return out;
        LocalDate date = ParserUtil.parseDate(ParserUtil.findFirst(text, DATE));
        if (date == null) date = LocalDate.now();

        boolean isCredit = ParserUtil.containsIgnoreCase(text, CREDIT_WORDS);
        boolean isDebit  = ParserUtil.containsIgnoreCase(text, DEBIT_WORDS);

        String bankName = extractBankName(from, text);

        if (isCredit && !isDebit) {
            String source;
            String payer = null;
            if (ParserUtil.containsIgnoreCase(text, "salary", "sal cr", "sal credit")) {
                source = "Salary";
                payer = extractSalaryPayer(text);
            } else if (ParserUtil.containsIgnoreCase(text, "interest")) {
                source = "Interest";
                payer = bankName;
            } else if (ParserUtil.containsIgnoreCase(text, "refund", "cashback", "reversal")) {
                source = "Other";
                payer = SpendCategorizer.extractMerchant(text);
            } else return out;
            String desc = payer != null ? source + " from " + payer : source;
            out.add(ParsedEmail.builder()
                .type(ParsedEmail.Type.INCOME)
                .incomeSource(source)
                .merchant(payer)
                .paymentMethod(bankName)
                .amount(amount)
                .tradeDate(date)
                .sourceDescription(String.format("%s: ₹%.0f", desc, amount))
                .build());
        } else if (isDebit) {
            String merchant = SpendCategorizer.extractMerchant(text);
            ExpenseCategory category = SpendCategorizer.categorize(text);
            if (category == ExpenseCategory.INVESTMENT) return out;
            String desc = merchant != null ? merchant
                : (category != ExpenseCategory.UNCATEGORIZED ? category.getLabel() + " spend" : "Expense");
            out.add(ParsedEmail.builder()
                .type(ParsedEmail.Type.EXPENSE)
                .category(category.getLabel())
                .merchant(merchant)
                .paymentMethod(bankName)
                .amount(amount)
                .tradeDate(date)
                .sourceDescription(String.format("%s: ₹%.0f%s", desc, amount,
                    bankName != null ? " via " + bankName : ""))
                .build());
        }
        return out;
    }

    private static final String[][] BANK_NAMES = {
        {"hdfc", "HDFC Bank"}, {"sbi", "SBI"}, {"icici", "ICICI Bank"},
        {"kotak", "Kotak Mahindra Bank"}, {"axis", "Axis Bank"}, {"yesbank", "Yes Bank"},
        {"yes bank", "Yes Bank"}, {"idfc", "IDFC FIRST Bank"}, {"indusind", "IndusInd Bank"},
        {"pnb", "PNB"}, {"bob", "Bank of Baroda"}, {"canara", "Canara Bank"},
        {"union bank", "Union Bank"}, {"federal bank", "Federal Bank"}, {"rbl", "RBL Bank"},
    };

    private static String extractBankName(String from, String text) {
        String combined = ((from != null ? from : "") + " " + (text != null ? text : "")).toLowerCase();
        for (String[] pair : BANK_NAMES) {
            if (combined.contains(pair[0])) return pair[1];
        }
        return null;
    }

    private static String extractSalaryPayer(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?:from|by|employer|company)[:\\s]+([A-Z][A-Za-z &.\\-]{2,40}?)(?:\\s+on|\\.|,|;|\\n|$)",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
