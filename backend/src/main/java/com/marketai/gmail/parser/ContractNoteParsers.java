package com.marketai.gmail.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixed-format readers for broker contract notes. Like {@link BankAlertParsers}, each produces
 * the extractor's JSON with each trade row as its evidence, so the checks, deduplication and
 * booking are unchanged — only exact, and without a model call.
 *
 * <p>The contract note is the one booking source for trades; the exchange's trade alerts and
 * the depository's statements only confirm what it books.
 */
public final class ContractNoteParsers {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ContractNoteParsers() {}

    // --- m.Stock -----------------------------------------------------------------------------
    // One row per fill:
    // "<order no> 12:35:05 <trade no> 12:35:05 NSE - M AXIS BANK LIMITED BUY 5 1234.8000 0.0000 1234.8000 0.0000 -6174.0000"
    //  order time, trade time, exchange - segment, security (cut at 20 characters), side, quantity,
    //  gross rate, brokerage per unit, net rate, closing rate, net total (negative for a buy).
    private static final Pattern MSTOCK_TRADE = Pattern.compile(
        "(\\d{4,})\\s+\\d{2}:\\d{2}:\\d{2}\\s+(\\d{3,})\\s+\\d{2}:\\d{2}:\\d{2}\\s+(NSE|BSE)\\s+-\\s+\\S+\\s+"
            + "(.+?)\\s+(BUY|SELL)\\s+(\\d+)\\s+(\\d+\\.\\d+)\\s+(\\d+\\.\\d+)\\s+(\\d+\\.\\d+)\\s+(-?\\d+\\.\\d+)\\s+(-?\\d+\\.\\d+)");
    // Futures and options rows ("NSEFO - BT_O OPTIDX NIFTY 13OCT26 22150.00 PE (BT) BUY 65 …").
    private static final Pattern MSTOCK_FNO = Pattern.compile(
        "(\\d{4,})\\s+\\d{2}:\\d{2}:\\d{2}\\s+(\\d{3,})\\s+\\d{2}:\\d{2}:\\d{2}\\s+(NSEFO|BSEFO|NFO|CDS|MCX)\\s+-\\s+\\S+\\s+"
            + "(.+?)\\s+(BUY|SELL)\\s+(\\d+)\\s+(\\d+\\.\\d+)[^\\r\\n]*");
    // Per-security summary row, which carries the ISIN: "INE397D01024 BHARTI AIRTEL LIMITE 95 1847.0000 …"
    private static final Pattern MSTOCK_ISIN = Pattern.compile("\\b(IN[EF][A-Z0-9]{8}\\d)\\s+(.+?)\\s+\\d+\\s+\\d+\\.\\d+");
    private static final Pattern TRADE_DATE = Pattern.compile(
        "Trade\\s*Date\\s*:?\\s*(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4}|\\d{1,2}[-\\s][A-Za-z]{3}[-\\s]\\d{4}|[A-Za-z]{3}\\s+\\d{1,2},?\\s+\\d{4})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBJECT_DATE = Pattern.compile("(?:as on|executed on)\\s+([A-Za-z]{3}\\s+\\d{1,2},?\\s+\\d{4})",
        Pattern.CASE_INSENSITIVE);

    /** Reads a contract note, or returns empty when it isn't one these parsers know. */
    public static Optional<BankAlertParsers.Result> parse(String senderDomain, String subject, String text) {
        if (text == null || !covers(senderDomain, subject)) return Optional.empty();
        return mstock(subject, text);
    }

    /** Whether a document from this sender with this subject is one these parsers read. */
    public static boolean covers(String senderDomain, String subject) {
        if (senderDomain == null || subject == null) return false;
        String d = senderDomain.toLowerCase(Locale.ROOT);
        return (d.equals("mstock.com") || d.endsWith(".mstock.com")) && subject.toLowerCase(Locale.ROOT).contains("contract note");
    }

    private static Optional<BankAlertParsers.Result> mstock(String subject, String text) {
        LocalDate date = tradeDate(subject, text);
        if (date == null) return Optional.empty();
        Map<String, String> isinByName = new HashMap<>();
        Matcher im = MSTOCK_ISIN.matcher(text);
        while (im.find()) isinByName.putIfAbsent(nameKey(im.group(2)), im.group(1));

        ArrayNode txns = JSON.createArrayNode();
        Matcher m = MSTOCK_TRADE.matcher(text);
        while (m.find()) {
            String name = m.group(4).trim();
            int qty = Integer.parseInt(m.group(6));
            BigDecimal rate = new BigDecimal(m.group(7));
            BigDecimal brokeragePerUnit = new BigDecimal(m.group(8));
            BigDecimal total = new BigDecimal(m.group(11)).abs();
            ObjectNode t = JSON.createObjectNode();
            t.put("instrument_type", "EQUITY");
            t.put("transaction_type", m.group(5));
            t.put("symbol", name);
            String isin = isinByName.get(nameKey(name));
            if (isin != null) t.put("isin", isin); else t.putNull("isin");
            t.put("quantity", qty);
            t.put("price", rate);
            BigDecimal charges = brokeragePerUnit.multiply(BigDecimal.valueOf(qty));
            if (charges.signum() > 0) t.put("charges_inr", charges); else t.putNull("charges_inr");
            t.put("trade_number", m.group(2));
            t.put("amount_inr", total);
            t.put("transaction_date", date.toString());
            t.put("status", "SUCCESS");
            t.put("currency", "INR");
            t.put("evidence", m.group().trim());
            txns.add(t);
        }
        Matcher f = MSTOCK_FNO.matcher(text);
        while (f.find()) {
            ObjectNode t = JSON.createObjectNode();
            t.put("instrument_type", "FNO");
            t.put("transaction_type", f.group(5));
            t.put("symbol", f.group(4).trim());
            t.put("quantity", Integer.parseInt(f.group(6)));
            t.put("price", new BigDecimal(f.group(7)));
            t.put("trade_number", f.group(2));
            t.put("amount_inr", new BigDecimal(f.group(7)).multiply(new BigDecimal(f.group(6))));
            t.put("transaction_date", date.toString());
            t.put("status", "SUCCESS");
            t.put("evidence", f.group().trim());
            txns.add(t);
        }
        if (txns.isEmpty()) return Optional.empty();
        ObjectNode root = JSON.createObjectNode();
        root.set("transactions", txns);
        root.set("closing_balances", JSON.createArrayNode());
        root.put("confidence", 1.0);
        return Optional.of(new BankAlertParsers.Result(root.toString(), "mstock-contract-note"));
    }

    /** "LARSEN & TOUBRO LTD." and "LARSEN & TOUBRO LTD" are the same security. */
    private static String nameKey(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9&]", "");
    }

    private static final List<DateTimeFormatter> DATES = List.of(
        DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"),
        DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH), DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM d uuuu", Locale.ENGLISH));

    /** The note's own trade date; failing that, the one in its subject ("Contract Note as on Oct 13 2026"). */
    static LocalDate tradeDate(String subject, String text) {
        Matcher m = TRADE_DATE.matcher(text);
        if (m.find()) {
            LocalDate d = parseDate(m.group(1));
            if (d != null) return d;
        }
        if (subject != null) {
            m = SUBJECT_DATE.matcher(subject);
            if (m.find()) return parseDate(m.group(1));
        }
        return null;
    }

    private static LocalDate parseDate(String raw) {
        String s = raw.replace(",", "").replaceAll("\\s+", " ").trim();
        // "OCT" and "oct" both read as "Oct".
        s = Pattern.compile("([A-Za-z])([A-Za-z]{2})").matcher(s)
            .replaceAll(r -> r.group(1).toUpperCase(Locale.ROOT) + r.group(2).toLowerCase(Locale.ROOT));
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(s, f);
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return null;
    }
}
