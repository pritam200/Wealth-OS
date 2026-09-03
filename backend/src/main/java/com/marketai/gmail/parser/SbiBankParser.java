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
public class SbiBankParser implements EmailParser {

    private static final Pattern AMOUNT    = Pattern.compile("(?:Amount|Principal|Deposit)[:\\s]+(?:Rs\\.?\\s*|INR\\s*|₹\\s*)?([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RATE      = Pattern.compile("(?:Rate|Interest)[:\\s]+(\\d+\\.?\\d*)\\s*%", Pattern.CASE_INSENSITIVE);
    private static final Pattern MATURITY  = Pattern.compile("(?:Maturity|Due\\s*Date)[:\\s]+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern START     = Pattern.compile("(?:Opening|Booking|Start)[:\\s]+(?:Date)?[:\\s]*(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern RD_MONTHLY= Pattern.compile("(?:Monthly|Instalment)[:\\s]+(?:Rs\\.?\\s*|₹\\s*)?([\\d,]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean canParse(String from, String subject) {
        return ParserUtil.containsIgnoreCase(from, "sbi", "statebank", "onlinesbi")
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
            String monthly = ParserUtil.findFirst(text, RD_MONTHLY);
            String rate    = ParserUtil.findFirst(text, RATE);
            BigDecimal m   = ParserUtil.parseMoney(monthly);
            if (m == null) return results;
            results.add(ParsedEmail.builder()
                .type(ParsedEmail.Type.RD_OPEN)
                .bank("SBI")
                .monthlyAmount(m)
                .rate(ParserUtil.parseMoney(rate) != null ? ParserUtil.parseMoney(rate) : BigDecimal.valueOf(6.8))
                .startDate(LocalDate.now())
                .tenureMonths(12)
                .sourceDescription(String.format("SBI RD: ₹%.0f/month", m))
                .build());
        } else {
            String amtStr = ParserUtil.findFirst(text, AMOUNT);
            String rateStr = ParserUtil.findFirst(text, RATE);
            String matStr  = ParserUtil.findFirst(text, MATURITY);
            String startStr= ParserUtil.findFirst(text, START);
            BigDecimal amt = ParserUtil.parseMoney(amtStr);
            if (amt == null) return results;
            results.add(ParsedEmail.builder()
                .type(ParsedEmail.Type.FD_OPEN)
                .bank("SBI")
                .principal(amt)
                .rate(ParserUtil.parseMoney(rateStr) != null ? ParserUtil.parseMoney(rateStr) : BigDecimal.valueOf(6.8))
                .startDate(ParserUtil.parseDate(startStr) != null ? ParserUtil.parseDate(startStr) : LocalDate.now())
                .maturityDate(ParserUtil.parseDate(matStr))
                .compounding("quarterly")
                .sourceDescription(String.format("SBI FD: ₹%.0f%s", amt, rateStr != null ? " @ " + rateStr + "%" : ""))
                .build());
        }
        return results;
    }
}
