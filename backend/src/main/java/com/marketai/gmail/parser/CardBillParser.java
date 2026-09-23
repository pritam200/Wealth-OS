package com.marketai.gmail.parser;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses credit-card statement / bill emails and produces a CARD_BILL
 * with the total amount due + due date, matched to a saved card by issuer/last4.
 */
@Component
@Order(1)
public class CardBillParser implements EmailParser {

    private static final Pattern DUE_AMOUNT = Pattern.compile(
        "(?:total\\s+amount\\s+due|total\\s+due|amount\\s+payable|total\\s+outstanding)[^\\d]{0,20}(?:Rs\\.?\\s*|INR\\s*|₹\\s*)?([\\d,]+\\.?\\d{0,2})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DUE_DATE = Pattern.compile(
        "(?:due\\s+date|payment\\s+due\\s+(?:by|on)|due\\s+by)[^\\d]{0,15}(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{2,4})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern STATEMENT_DATE = Pattern.compile(
        "(?:statement\\s+date|statement\\s+generated\\s+on|bill\\s+date|generated\\s+on)[^\\d]{0,15}(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{2,4})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST4 = Pattern.compile(
        "(?:card\\s*(?:no|number|ending|xx+)[^\\d]{0,8}|\\*{2,}\\s*)(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINIMUM_DUE = Pattern.compile(
        "(?:minimum\\s+(?:amount\\s+)?due|min\\.?\\s*amount\\s+due|min\\s+due)[^\\d]{0,20}(?:Rs\\.?\\s*|INR\\s*|₹\\s*)?([\\d,]+\\.?\\d{0,2})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PREVIOUS_BALANCE = Pattern.compile(
        "(?:previous\\s+balance|previous\\s+outstanding|opening\\s+balance|prev(?:ious)?\\.?\\s*due)[^\\d]{0,20}(?:Rs\\.?\\s*|INR\\s*|₹\\s*)?([\\d,]+\\.?\\d{0,2})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CYCLE_DEBITS = Pattern.compile(
        "(?:purchases\\s*(?:&|and)?\\s*(?:other\\s*)?debits|total\\s+debits|total\\s+purchases)[^\\d]{0,20}(?:Rs\\.?\\s*|INR\\s*|₹\\s*)?([\\d,]+\\.?\\d{0,2})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CYCLE_CREDITS = Pattern.compile(
        "(?:payments\\s*(?:&|and)?\\s*(?:other\\s*)?credits|total\\s+credits|total\\s+payments)[^\\d]{0,20}(?:Rs\\.?\\s*|INR\\s*|₹\\s*)?([\\d,]+\\.?\\d{0,2})",
        Pattern.CASE_INSENSITIVE);

    private static final String[] ISSUERS = { "hdfc", "sbi", "icici", "axis", "amex", "americanexpress", "kotak", "citi", "hsbc", "rbl", "idfc" };

    @Override
    public boolean canParse(String from, String subject) {
        boolean issuer = ParserUtil.containsIgnoreCase(from, ISSUERS) || ParserUtil.containsIgnoreCase(subject, "card");
        boolean bill   = ParserUtil.containsIgnoreCase(subject, "statement", "bill", "amount due", "payment due", "credit card statement");
        return issuer && bill;
    }

    @Override
    public List<ParsedEmail> parse(String from, String subject, String bodyText) {
        List<ParsedEmail> out = new ArrayList<>();
        String text = (subject == null ? "" : subject) + "\n" + (bodyText == null ? "" : bodyText);

        BigDecimal due = ParserUtil.parseMoney(ParserUtil.findFirst(text, DUE_AMOUNT));
        if (due == null) return out;
        LocalDate dueDate = ParserUtil.parseDate(ParserUtil.findFirst(text, DUE_DATE));
        LocalDate statementDate = ParserUtil.parseDate(ParserUtil.findFirst(text, STATEMENT_DATE));
        String last4 = ParserUtil.findFirst(text, LAST4);
        BigDecimal minimumDue = ParserUtil.parseMoney(ParserUtil.findFirst(text, MINIMUM_DUE));
        BigDecimal previousBalance = ParserUtil.parseMoney(ParserUtil.findFirst(text, PREVIOUS_BALANCE));
        BigDecimal cycleDebits = ParserUtil.parseMoney(ParserUtil.findFirst(text, CYCLE_DEBITS));
        BigDecimal cycleCredits = ParserUtil.parseMoney(ParserUtil.findFirst(text, CYCLE_CREDITS));

        String issuer = "Card";
        for (String is : ISSUERS) {
            if (ParserUtil.containsIgnoreCase(from + " " + subject, is)) {
                issuer = is.equalsIgnoreCase("americanexpress") ? "Amex" : is.toUpperCase();
                break;
            }
        }

        out.add(ParsedEmail.builder()
            .type(ParsedEmail.Type.CARD_BILL)
            .bank(issuer)
            .cardLast4(last4)
            .amount(due)
            .dueDate(dueDate)
            .statementDate(statementDate)
            .minimumDue(minimumDue)
            .previousBalance(previousBalance)
            .cycleDebits(cycleDebits)
            .cycleCredits(cycleCredits)
            .sourceDescription(String.format("%s card bill: ₹%.0f due%s", issuer, due,
                dueDate != null ? " by " + dueDate : ""))
            .build());
        return out;
    }
}
