package com.marketai.gmail.parser;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses credit-card *payment confirmation* emails — "your payment towards card ending 1234 was
 * received/successful" — into a CARD_PAYMENT. This is evidence that money moved, never an
 * instruction to move it: Wealth-OS does not initiate, retry, or execute any payment. The record
 * exists purely so {@code CardReconciliationService} can later explain "did I pay" and "how much
 * is outstanding" against the statements already on file.
 *
 * <p>Also detects reversal/failure language ("payment failed", "reversed", "declined") and tags
 * the result {@code REVERSED} rather than {@code CONFIRMED} — a bank sometimes sends a failure
 * notice using the same subject/template as a success notice, so this must not be inferred from
 * {@link #canParse} alone.
 */
@Component
@Order(1)
public class CardPaymentParser implements EmailParser {

    private static final Pattern AMOUNT = Pattern.compile(
        "(?:payment\\s+of|amount\\s+of|received\\s+payment\\s+of|paid)[^\\d]{0,20}(?:Rs\\.?\\s*|INR\\s*|₹\\s*)?([\\d,]+\\.?\\d{0,2})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYMENT_DATE = Pattern.compile(
        "(?:payment\\s+date|paid\\s+on|received\\s+on|on)[^\\d]{0,15}(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{2,4})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE = Pattern.compile(
        "(?:reference\\s*(?:no\\.?|number)?|ref\\s*(?:no\\.?)?|rrn|utr|transaction\\s*id)[^A-Za-z0-9]{0,5}([A-Za-z0-9]{6,25})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST4 = Pattern.compile(
        "(?:card\\s*(?:no|number|ending|xx+)[^\\d]{0,8}|\\*{2,}\\s*)(\\d{4})\\b", Pattern.CASE_INSENSITIVE);

    private static final String[] ISSUERS = { "hdfc", "sbi", "icici", "axis", "amex", "americanexpress", "kotak", "citi", "hsbc", "rbl", "idfc" };
    private static final String[] PAYMENT_KEYWORDS = {
        "payment received", "payment successful", "payment of", "bill payment", "credit card payment",
        "payment failed", "payment declined", "payment reversed", "transaction reversed"
    };
    private static final String[] REVERSAL_KEYWORDS = { "failed", "declined", "reversed", "unsuccessful", "not processed" };

    @Override
    public boolean canParse(String from, String subject) {
        boolean issuer = ParserUtil.containsIgnoreCase(from, ISSUERS) || ParserUtil.containsIgnoreCase(subject, "card");
        boolean payment = ParserUtil.containsIgnoreCase(subject, PAYMENT_KEYWORDS);
        return issuer && payment;
    }

    @Override
    public List<ParsedEmail> parse(String from, String subject, String bodyText) {
        List<ParsedEmail> out = new ArrayList<>();
        String text = (subject == null ? "" : subject) + "\n" + (bodyText == null ? "" : bodyText);

        BigDecimal amount = ParserUtil.parseMoney(ParserUtil.findFirst(text, AMOUNT));
        if (amount == null) return out;

        LocalDate paymentDate = ParserUtil.parseDate(ParserUtil.findFirst(text, PAYMENT_DATE));
        String reference = ParserUtil.findFirst(text, REFERENCE);
        String last4 = ParserUtil.findFirst(text, LAST4);

        String issuer = "Card";
        for (String is : ISSUERS) {
            if (ParserUtil.containsIgnoreCase(from + " " + subject, is)) {
                issuer = is.equalsIgnoreCase("americanexpress") ? "Amex" : is.toUpperCase();
                break;
            }
        }

        // A reversal/failure notice is evidence too — recording it (rather than skipping it) is
        // what lets reconciliation exclude it from the settled-amount waterfall instead of never
        // knowing it existed.
        boolean reversed = ParserUtil.containsIgnoreCase(text, REVERSAL_KEYWORDS);
        String status = reversed ? "REVERSED" : "CONFIRMED";

        out.add(ParsedEmail.builder()
            .type(ParsedEmail.Type.CARD_PAYMENT)
            .bank(issuer)
            .cardLast4(last4)
            .amount(amount)
            .paymentDate(paymentDate != null ? paymentDate : LocalDate.now())
            .paymentReference(reference)
            .paymentStatus(status)
            .sourceDescription(String.format("%s card payment %s: ₹%.0f%s", issuer,
                reversed ? "reversed/failed" : "received", amount,
                paymentDate != null ? " on " + paymentDate : ""))
            .build());
        return out;
    }
}
