package com.marketai.gmail.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixed-format readers for the registrars' fund emails. Like {@link BankAlertParsers}, each
 * produces the extractor's JSON with the sentence it read as the evidence, so the scheme match,
 * checks, deduplication and booking are unchanged — only exact, and without a model call.
 *
 * <p>The purchase confirmation is the one booking source for a fund purchase: it states the
 * NAV and units allotted. The SIP-instalment intimation only says a payment is due, so it books
 * nothing; the confirmation books that instalment once it has been processed.
 */
public final class FundEmailParsers {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FundEmailParsers() {}

    // "We confirm having processed your purchase in SBI Aggressive Hybrid Fund - Direct Growth for
    //  value date 25-Sep-2026 for Rs. 499.98 at NAV of 344.1641 for units 1.453 in Folio No. 23655953."
    private static final Pattern CAMS_PURCHASE = Pattern.compile(
        "processed\\s+your\\s+purchase\\s+in\\s+(.+?)\\s+for\\s+value\\s+date\\s+(\\d{1,2}-[A-Za-z]{3}-\\d{4})\\s+"
            + "for\\s+Rs\\.?\\s*([\\d,]+(?:\\.\\d+)?)\\s+at\\s+NAV\\s+of\\s+([\\d,]+(?:\\.\\d+)?)\\s+"
            + "for\\s+units\\s+([\\d,]+(?:\\.\\d+)?)\\s+in\\s+Folio\\s+No\\.?\\s*([A-Za-z0-9/]+)",
        Pattern.CASE_INSENSITIVE);
    // "your SIP instalment of Rs. 500 in SBI Aggressive Hybrid Fund Dir Growth under Folio 23655953,
    //  Frequency Daily(ZIP) is due on 07-OCT-2026."
    private static final Pattern CAMS_SIP_DUE = Pattern.compile(
        "SIP\\s+instal+ment\\s+of\\s+Rs\\.?\\s*[\\d,.]+\\s+in\\s+.+?\\s+under\\s+Folio\\s+\\S+.*?is\\s+due\\s+on\\s+\\d{1,2}-[A-Za-z]{3}-\\d{4}",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final DateTimeFormatter DATE = new DateTimeFormatterBuilder()
        .parseCaseInsensitive().appendPattern("d-MMM-uuuu").toFormatter(Locale.ENGLISH);

    /** Reads a registrar's fund email, or returns empty when it isn't a template these parsers know. */
    public static Optional<BankAlertParsers.Result> parse(String senderDomain, String subject, String text) {
        if (senderDomain == null || text == null || !isCams(senderDomain)) return Optional.empty();

        ArrayNode txns = JSON.createArrayNode();
        Matcher m = CAMS_PURCHASE.matcher(text);
        while (m.find()) {
            LocalDate date = LocalDate.parse(m.group(2), DATE);
            ObjectNode t = JSON.createObjectNode();
            t.put("instrument_type", "MF");
            t.put("transaction_type", "PURCHASE");
            t.put("scheme_name", m.group(1).replaceAll("\\s+", " ").trim());
            t.put("transaction_date", date.toString());
            t.put("amount_inr", number(m.group(3)));
            t.put("nav", number(m.group(4)));
            t.put("units", number(m.group(5)));
            t.put("folio_number", m.group(6));
            t.put("status", "SUCCESS");
            t.put("currency", "INR");
            t.put("evidence", m.group().replaceAll("\\s+", " ").trim());
            txns.add(t);
        }
        if (!txns.isEmpty()) return Optional.of(result(txns, "cams-purchase-confirmation"));
        if (CAMS_SIP_DUE.matcher(text).find()) return Optional.of(result(txns, "cams-sip-due"));
        return Optional.empty();
    }

    private static boolean isCams(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        for (String c : new String[]{"camsonline.com", "camsonline.co.in", "cams.com"})
            if (d.equals(c) || d.endsWith("." + c)) return true;
        return false;
    }

    private static BigDecimal number(String raw) {
        return new BigDecimal(raw.replace(",", ""));
    }

    private static BankAlertParsers.Result result(ArrayNode txns, String parser) {
        ObjectNode root = JSON.createObjectNode();
        root.set("transactions", txns);
        root.set("closing_balances", JSON.createArrayNode());
        root.put("confidence", 1.0);
        return new BankAlertParsers.Result(root.toString(), parser);
    }
}
