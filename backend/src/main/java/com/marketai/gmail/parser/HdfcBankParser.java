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
public class HdfcBankParser implements EmailParser {

    // FD patterns
    private static final Pattern FD_AMOUNT   = Pattern.compile("(?:Amount|Principal|FD\\s+Amount)[:\\s]+(?:Rs\\.?\\s*|INR\\s*|₹\\s*)?([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FD_RATE     = Pattern.compile("(?:Rate|Interest\\s*Rate|ROI)[:\\s]+(\\d+\\.?\\d*)\\s*%", Pattern.CASE_INSENSITIVE);
    private static final Pattern FD_MATURITY = Pattern.compile("(?:Maturity\\s*Date|Due\\s*Date|Value\\s*Date)[:\\s]+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern FD_START    = Pattern.compile("(?:Booking\\s*Date|Start\\s*Date|Opening\\s*Date|Date\\s*of\\s*Booking)[:\\s]+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern FD_TENURE   = Pattern.compile("(?:Tenure|Period|Term)[:\\s]+(\\d+)\\s*(?:Month|Year|Day)", Pattern.CASE_INSENSITIVE);

    // RD patterns
    private static final Pattern RD_MONTHLY  = Pattern.compile("(?:Monthly\\s*(?:Instalment|Installment|Amount|SIP))[:\\s]+(?:Rs\\.?\\s*|₹\\s*)?([\\d,]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean canParse(String from, String subject) {
        boolean fromHdfc = ParserUtil.containsIgnoreCase(from, "hdfcbank", "hdfc");
        boolean isFdRd   = ParserUtil.containsIgnoreCase(subject, "fixed deposit", "fd booking", "recurring deposit", "rd booking")
            || subject != null && subject.matches("(?i).*\\bFD\\b.*|.*\\bRD\\b.*");
        return fromHdfc && isFdRd;
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
            String rateStr    = ParserUtil.findFirst(text, FD_RATE);
            String startStr   = ParserUtil.findFirst(text, FD_START);
            String tenureStr  = ParserUtil.findFirst(text, FD_TENURE);

            BigDecimal monthly = ParserUtil.parseMoney(monthlyStr);
            if (monthly == null) return results;

            results.add(ParsedEmail.builder()
                .type(ParsedEmail.Type.RD_OPEN)
                .bank("HDFC Bank")
                .monthlyAmount(monthly)
                .rate(ParserUtil.parseMoney(rateStr) != null ? ParserUtil.parseMoney(rateStr) : BigDecimal.valueOf(7.0))
                .startDate(ParserUtil.parseDate(startStr) != null ? ParserUtil.parseDate(startStr) : LocalDate.now())
                .tenureMonths(tenureStr != null ? ParserUtil.parseIntSafe(tenureStr, 12) : 12)
                .sourceDescription(String.format("HDFC RD: ₹%.0f/month", monthly))
                .build());
        } else {
            String amountStr   = ParserUtil.findFirst(text, FD_AMOUNT);
            String rateStr     = ParserUtil.findFirst(text, FD_RATE);
            String maturityStr = ParserUtil.findFirst(text, FD_MATURITY);
            String startStr    = ParserUtil.findFirst(text, FD_START);

            BigDecimal amount = ParserUtil.parseMoney(amountStr);
            if (amount == null) return results;

            results.add(ParsedEmail.builder()
                .type(ParsedEmail.Type.FD_OPEN)
                .bank("HDFC Bank")
                .principal(amount)
                .rate(ParserUtil.parseMoney(rateStr) != null ? ParserUtil.parseMoney(rateStr) : BigDecimal.valueOf(7.0))
                .startDate(ParserUtil.parseDate(startStr) != null ? ParserUtil.parseDate(startStr) : LocalDate.now())
                .maturityDate(ParserUtil.parseDate(maturityStr))
                .compounding("quarterly")
                .sourceDescription(String.format("HDFC FD: ₹%.0f%s", amount, rateStr != null ? " @ " + rateStr + "%" : ""))
                .build());
        }
        return results;
    }
}
