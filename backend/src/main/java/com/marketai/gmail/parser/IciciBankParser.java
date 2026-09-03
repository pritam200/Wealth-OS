package com.marketai.gmail.parser;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
@Order(1)
public class IciciBankParser implements EmailParser {

    private static final Pattern AMOUNT   = Pattern.compile("(?:FD\\s+Amount|Principal|Deposit\\s+Amount|Amount)[:\\s]+(?:Rs\\.?\\s*|INR\\s*|₹\\s*)?([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RATE     = Pattern.compile("(?:Rate|Interest\\s+Rate)[:\\s]+(\\d+\\.?\\d*)\\s*%", Pattern.CASE_INSENSITIVE);
    private static final Pattern MATURITY = Pattern.compile("(?:Maturity|Value\\s+Date)[:\\s]+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern START    = Pattern.compile("(?:Booking\\s+Date|Opening\\s+Date|Date)[:\\s]+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TENURE   = Pattern.compile("(?:Tenure|Period|Term)[:\\s]+(\\d+)\\s*(?:Month|Year|Day)", Pattern.CASE_INSENSITIVE);

    // RD patterns
    private static final Pattern RD_MONTHLY = Pattern.compile("(?:Monthly\\s*(?:Instalment|Installment|Amount|SIP))[:\\s]+(?:Rs\\.?\\s*|₹\\s*)?([\\d,]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean canParse(String from, String subject) {
        return ParserUtil.containsIgnoreCase(from, "icici", "icicibank")
            && (ParserUtil.containsIgnoreCase(subject, "fixed deposit", "fd booking", "recurring deposit", "rd booking", "deposit")
                || subject != null && subject.matches("(?i).*\\bFD\\b.*|.*\\bRD\\b.*"));
    }

    @Override
    public List<ParsedEmail> parse(String from, String subject, String bodyText) {
        List<ParsedEmail> results = new ArrayList<>();
        String text = bodyText != null ? bodyText : "";
        String combined = subject + " " + text;
        boolean isRd = ParserUtil.containsIgnoreCase(combined, "recurring deposit", "rd booking")
            || combined.matches("(?is).*\\bRD\\b(?!.*card).*");

        if (isRd) {
            String monthlyStr = ParserUtil.findFirst(text, RD_MONTHLY);
            String rateStr    = ParserUtil.findFirst(text, RATE);
            String startStr   = ParserUtil.findFirst(text, START);
            String tenureStr  = ParserUtil.findFirst(text, TENURE);

            BigDecimal monthly = ParserUtil.parseMoney(monthlyStr);
            if (monthly == null) return results;

            results.add(ParsedEmail.builder()
                .type(ParsedEmail.Type.RD_OPEN)
                .bank("ICICI Bank")
                .monthlyAmount(monthly)
                .rate(ParserUtil.parseMoney(rateStr) != null ? ParserUtil.parseMoney(rateStr) : BigDecimal.valueOf(7.1))
                .startDate(ParserUtil.parseDate(startStr) != null ? ParserUtil.parseDate(startStr) : LocalDate.now())
                .tenureMonths(tenureStr != null ? ParserUtil.parseIntSafe(tenureStr, 12) : 12)
                .sourceDescription(String.format("ICICI RD: ₹%.0f/month", monthly))
                .build());
        } else {
            String amtStr  = ParserUtil.findFirst(text, AMOUNT);
            String rateStr = ParserUtil.findFirst(text, RATE);
            String matStr  = ParserUtil.findFirst(text, MATURITY);
            String startStr= ParserUtil.findFirst(text, START);

            BigDecimal amt = ParserUtil.parseMoney(amtStr);
            if (amt == null) return results;

            results.add(ParsedEmail.builder()
                .type(ParsedEmail.Type.FD_OPEN)
                .bank("ICICI Bank")
                .principal(amt)
                .rate(ParserUtil.parseMoney(rateStr) != null ? ParserUtil.parseMoney(rateStr) : BigDecimal.valueOf(7.1))
                .startDate(ParserUtil.parseDate(startStr) != null ? ParserUtil.parseDate(startStr) : LocalDate.now())
                .maturityDate(ParserUtil.parseDate(matStr))
                .compounding("quarterly")
                .sourceDescription(String.format("ICICI FD: ₹%.0f%s", amt, rateStr != null ? " @ " + rateStr + "%" : ""))
                .build());
        }
        return results;
    }
}
