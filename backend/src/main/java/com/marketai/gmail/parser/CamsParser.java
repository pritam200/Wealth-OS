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
public class CamsParser implements EmailParser {

    // Longer/more-specific alternatives are listed first in each group (e.g. "Scheme Name"
    // before bare "Scheme") — Java regex alternation takes the first alternative that
    // matches at a position, so listing the bare label first would let it match, then fail
    // the immediately-following `\s*[:]` when the real text has an extra word ("Name")
    // before the colon, causing the whole pattern to miss real-world "Scheme Name:",
    // "Fund Name:" and "Amount Paid:" style labels entirely (the exact regression that let
    // the SBI purchase-confirmation email through unparsed).
    private static final Pattern FUND   = Pattern.compile(
        "(?:Scheme\\s*Name|Fund\\s*Name|Plan\\s*Name|Scheme|Fund|Plan)\\s*[:]\\s*([A-Za-z][A-Za-z0-9 &.,\\-()/]+?)\\s*(?:\\r?\\n|\\||$)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile(
        "(?:SIP\\s+Amount|Purchase\\s+Amount|Investment\\s+Amount|Amount\\s+Paid|Net\\s+Amount|Amount)[:\\s]+(?:Rs\\.?\\s*|INR\\s*|₹\\s*)?([\\d,]+\\.?\\d*)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern NAV    = Pattern.compile(
        "(?:Applicable\\s+NAV|NAV\\s*(?:per\\s*Unit)?)[:\\s]+(?:Rs\\.?\\s*|₹\\s*)?([\\d,]+\\.?\\d*)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern UNITS  = Pattern.compile(
        "(?:Units\\s+Allotted|No\\.?\\s+of\\s+Units|Units)[:\\s]+([\\d,]+\\.?\\d*)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE   = Pattern.compile("(?:Transaction\\s+Date|Date)[:\\s]+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLIO  = Pattern.compile("Folio\\s*(?:No\\.?|Number)?\\s*[:]\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMC    = Pattern.compile("(?:AMC|Fund\\s*House|Asset\\s*Management)\\s*[:]\\s*([A-Za-z][A-Za-z0-9 &.-]+?)(?:\\n|\\r|$)", Pattern.CASE_INSENSITIVE);

    private static final String[][] PROVIDER_PREFIXES = {
        {"ICICI Prudential", "ICICI Prudential"}, {"ICICI Pru", "ICICI Prudential"},
        {"SBI", "SBI"}, {"HDFC", "HDFC"}, {"Nippon India", "Nippon"}, {"Nippon", "Nippon"},
        {"Mirae Asset", "Mirae Asset"}, {"Axis", "Axis"}, {"Kotak", "Kotak"},
        {"Tata", "Tata"}, {"UTI", "UTI"}, {"DSP", "DSP"}, {"Aditya Birla", "Aditya Birla"}
    };

    @Override
    public boolean canParse(String from, String subject) {
        return ParserUtil.containsIgnoreCase(from, "cams", "camsonline")
            && ParserUtil.containsIgnoreCase(subject, "sip", "purchase", "transaction", "allotment", "confirmation", "redemption", "switch", "statement");
    }

    @Override
    public List<ParsedEmail> parse(String from, String subject, String bodyText) {
        List<ParsedEmail> results = new ArrayList<>();
        String text = bodyText != null ? bodyText : "";

        String fundName = ParserUtil.findFirst(text, FUND);
        String amtStr   = ParserUtil.findFirst(text, AMOUNT);
        String navStr   = ParserUtil.findFirst(text, NAV);
        String unitsStr = ParserUtil.findFirst(text, UNITS);
        String dateStr  = ParserUtil.findFirst(text, DATE);
        String folio    = ParserUtil.findFirst(text, FOLIO);
        String provider = ParserUtil.findFirst(text, AMC);

        // Infer provider from fund name if not found via AMC pattern
        if (provider == null && fundName != null) {
            provider = inferProvider(fundName);
        }
        if (provider != null) {
            provider = provider.trim();
        }

        BigDecimal amount = ParserUtil.parseMoney(amtStr);
        if (fundName == null || amount == null) return results;

        LocalDate tradeDate = ParserUtil.parseDate(dateStr);

        // Determine transaction type
        boolean isRedemption = ParserUtil.containsIgnoreCase(subject, "redemption", "redeem")
            || ParserUtil.containsIgnoreCase(text, "redemption", "redeem");
        ParsedEmail.Type type = isRedemption ? ParsedEmail.Type.MF_REDEEM : ParsedEmail.Type.MF_SIP;
        String typeLabel = isRedemption ? "Redemption" : "SIP";

        results.add(ParsedEmail.builder()
            .type(type)
            .fundName(fundName.trim())
            .amount(amount)
            .nav(ParserUtil.parseMoney(navStr))
            .units(ParserUtil.parseMoney(unitsStr))
            .tradeDate(tradeDate != null ? tradeDate : LocalDate.now())
            .folio(folio)
            .provider(provider)
            .sourceDescription(String.format("CAMS MF %s: %s ₹%.0f", typeLabel, fundName.trim(), amount))
            .build());
        return results;
    }

    private static String inferProvider(String fundName) {
        String upper = fundName.toUpperCase();
        for (String[] pair : PROVIDER_PREFIXES) {
            if (upper.startsWith(pair[0].toUpperCase()) || upper.contains(pair[0].toUpperCase())) {
                return pair[1];
            }
        }
        return null;
    }
}
