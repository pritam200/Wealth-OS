package com.marketai.gmail.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixed-format readers for the transaction alerts banks send, one per template. Each produces
 * exactly the JSON the AI extractor would ({@code {"transactions":[...],"confidence":1}}), with
 * the matched text as evidence, so the result goes through the same checks, deduplication and
 * booking as before — only without a model call, and with the same answer every time.
 *
 * <p>A template that isn't recognised returns empty and the email falls back to the AI
 * extractor. A template that is recognised but carries no transaction (an SBI hold) returns an
 * empty transaction list.
 */
public final class BankAlertParsers {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a parser read: the extractor-shaped JSON and which template it matched. */
    public record Result(String json, String parser) {}

    private BankAlertParsers() {}

    // --- Axis Bank -------------------------------------------------------------------------
    // "Amount Debited: INR 150.00  Account Number: XX3054  Date & Time: 15-10-26, 20:38:27 IST
    //  Transaction Info: UPI/P2M/123456/REKYC UPDATE"
    private static final Pattern AXIS_UPI = Pattern.compile(
        "Amount (Debited|Credited):\\s*INR\\s*([\\d,]+(?:\\.\\d+)?)\\s*Account Number:\\s*(XX\\d{3,6})\\s*"
            + "Date & Time:\\s*(\\d{2}-\\d{2}-\\d{2,4}),?\\s*[\\d:]*\\s*(?:IST)?\\s*Transaction Info:\\s*([^\\r\\n]+)",
        Pattern.CASE_INSENSITIVE);
    // "your A/c no. XX6248 has been debited with INR 500.00 on 08-10-2026 00:09:29 IST by HDFC Small Ca/5072/HA."
    private static final Pattern AXIS_ACCOUNT = Pattern.compile(
        "A/c no\\.\\s*(XX\\d{3,6}) has been (debited|credited) with INR\\s*([\\d,]+(?:\\.\\d+)?) on "
            + "(\\d{2}-\\d{2}-\\d{4})[\\d: ]*(?:IST)?\\s*by\\s+([^\\r\\n]+?)\\.?\\s*(?:$|\\r|\\n)",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // --- HDFC Bank -------------------------------------------------------------------------
    // "Rs.13000.00 is debited from your account ending 2548 towards VPA abc@ybl (NAME) on 26-09-26."
    // "Rs.141.50 has been debited from your RuPay Credit Card (ending 4286) Paid to X@ybl Date: 11-10-26"
    private static final Pattern HDFC_UPI = Pattern.compile(
        "Rs\\.?\\s?([\\d,]+(?:\\.\\d+)?) (?:has been|is) debited from your "
            + "(RuPay Credit Card \\(ending (\\d{4})\\)|account ending (\\d{4}))[\\s\\S]{0,120}?"
            + "(?:towards VPA|Paid to)\\s+(\\S+)(?:\\s+\\(([^)]+)\\))?[\\s\\S]{0,80}?(?:\\bon|Date:)\\s*(\\d{2}-\\d{2}-\\d{2,4})",
        Pattern.CASE_INSENSITIVE);
    // "Rs.5000.00 has been debited from HDFC Bank Account Number XXXXXXXXXX2548 towards HDFC Mutual Fund
    //  OTM/0633 with UMRN HDFC2408 on 22-Oct-2026."
    private static final Pattern HDFC_MANDATE = Pattern.compile(
        "Rs\\.?\\s?([\\d,]+(?:\\.\\d+)?) has been debited from HDFC Bank Account Number (X*\\d{4}) towards "
            + "(.+?)(?:\\s+OTM\\S*)?\\s+with UMRN \\S+ on (\\d{1,2}-[A-Za-z]{3}-\\d{4})",
        Pattern.CASE_INSENSITIVE);

    // --- SBI ("CBSSBI ALERT") ----------------------------------------------------------------
    // "Your A/C XXXXX953801 Debited INR 88.00 on 20/10/26 -Transferred to Mr. PRITAM KUMARSI."
    // "Your A/C XXXXX429546 Credited INR 88.00 on 20/10/26 -Deposit by transfer from Mr. Pritam Kumar."
    private static final Pattern SBI_DEBIT_CREDIT = Pattern.compile(
        "Your A/C (X+\\d{3,6}) (Debited|Credited) INR\\s*([\\d,]+(?:\\.\\d+)?) on (\\d{2}/\\d{2}/\\d{2,4})\\s*-\\s*"
            + "(?:Transferred to|Deposit by transfer from|by|to|from)?\\s*([^.\\r\\n]*(?:\\.[^.\\r\\n]+)?)\\.",
        Pattern.CASE_INSENSITIVE);
    // "Your A/C XXXXX429546 has a debit by transfer of Rs 4,000.00 on 05/10/26."
    // "Your A/C XXXXX429546 has credit for CTS6114WA of Rs 28.00 on 05/10/26."
    private static final Pattern SBI_HAS = Pattern.compile(
        "Your A/C (X+\\d{3,6}) has (?:a )?(debit|credit) (?:by transfer|for ([^\\s]+)) of Rs\\.?\\s*([\\d,]+(?:\\.\\d+)?) "
            + "on (\\d{2}/\\d{2}/\\d{2,4})",
        Pattern.CASE_INSENSITIVE);
    // A hold placed or released on the account: no money has moved yet.
    private static final Pattern SBI_HOLD = Pattern.compile("\\bhold for (?:INR|Rs\\.?)\\s*[\\d,.]+ (?:created|has been removed)",
        Pattern.CASE_INSENSITIVE);

    /**
     * Reads a bank alert, or returns empty when its template isn't one of these.
     *
     * @param holderName the account holder's name, to tell a transfer to oneself from a payment
     */
    public static Optional<Result> parse(String senderDomain, String subject, String body, String holderName) {
        if (senderDomain == null || body == null) return Optional.empty();
        String d = senderDomain.toLowerCase(Locale.ROOT);
        if (d.endsWith("axis.bank.in") || d.endsWith("axisbank.com")) return axis(body);
        if (d.endsWith("hdfcbank.bank.in") || d.endsWith("hdfcbank.net") || d.endsWith("hdfcbank.com")) return hdfc(body);
        if (d.endsWith("sbi.bank.in") || d.endsWith("sbi.co.in")) return sbi(body, holderName);
        return Optional.empty();
    }

    private static Optional<Result> axis(String body) {
        ArrayNode txns = JSON.createArrayNode();
        Matcher m = AXIS_UPI.matcher(body);
        while (m.find()) {
            String info = m.group(5).trim();
            txns.add(txn("UPI", direction(m.group(1)), amount(m.group(2)), date(m.group(4)),
                lastSegment(info), "Axis Bank " + m.group(3), m.group()));
        }
        if (txns.isEmpty()) {
            m = AXIS_ACCOUNT.matcher(body);
            while (m.find()) {
                txns.add(txn("BANK", direction(m.group(2)), amount(m.group(3)), date(m.group(4)),
                    firstSegment(m.group(5)), "Axis Bank " + m.group(1), m.group().trim()));
            }
        }
        return txns.isEmpty() ? Optional.empty() : Optional.of(result(txns, "axis-alert"));
    }

    private static Optional<Result> hdfc(String body) {
        ArrayNode txns = JSON.createArrayNode();
        Matcher m = HDFC_UPI.matcher(body);
        while (m.find()) {
            String payee = m.group(6) != null ? m.group(6).trim() : m.group(5);
            String method = m.group(3) != null ? "HDFC RuPay Credit Card " + m.group(3) : "HDFC Bank " + m.group(4);
            txns.add(txn("UPI", "DEBIT", amount(m.group(1)), date(m.group(7)), payee, method, m.group()));
        }
        m = HDFC_MANDATE.matcher(body);
        while (m.find()) {
            txns.add(txn("BANK", "DEBIT", amount(m.group(1)), date(m.group(4)), m.group(3).trim(),
                "HDFC Bank " + last4(m.group(2)), m.group()));
        }
        return txns.isEmpty() ? Optional.empty() : Optional.of(result(txns, "hdfc-alert"));
    }

    private static Optional<Result> sbi(String body, String holderName) {
        ArrayNode txns = JSON.createArrayNode();
        Matcher m = SBI_DEBIT_CREDIT.matcher(body);
        while (m.find()) {
            String party = cleanParty(m.group(5));
            ObjectNode t = txn("BANK", direction(m.group(2)), amount(m.group(3)), date(m.group(4)),
                party, "SBI " + last4(m.group(1)), m.group());
            if (isHolder(party, holderName)) t.put("sub_type", "OWN_TRANSFER");
            txns.add(t);
        }
        m = SBI_HAS.matcher(body);
        while (m.find()) {
            txns.add(txn("BANK", direction(m.group(2)), amount(m.group(4)), date(m.group(5)),
                m.group(3), "SBI " + last4(m.group(1)), m.group()));
        }
        if (txns.isEmpty() && SBI_HOLD.matcher(body).find()) return Optional.of(result(txns, "sbi-hold"));
        return txns.isEmpty() ? Optional.empty() : Optional.of(result(txns, "sbi-alert"));
    }

    private static ObjectNode txn(String instrument, String direction, BigDecimal amount, LocalDate date,
                                  String merchant, String paymentMethod, String evidence) {
        ObjectNode t = JSON.createObjectNode();
        t.put("instrument_type", instrument);
        t.put("transaction_type", direction);
        t.putNull("sub_type");
        t.put("amount_inr", amount);
        t.put("transaction_date", date == null ? null : date.toString());
        if (merchant == null || merchant.isBlank()) t.putNull("merchant"); else t.put("merchant", merchant.trim());
        t.put("payment_method", paymentMethod);
        t.put("status", "SUCCESS");
        t.put("currency", "INR");
        t.put("evidence", evidence.trim());
        return t;
    }

    private static Result result(ArrayNode txns, String parser) {
        ObjectNode root = JSON.createObjectNode();
        root.set("transactions", txns);
        root.set("closing_balances", JSON.createArrayNode());
        root.put("confidence", 1.0);
        return new Result(root.toString(), parser);
    }

    private static String direction(String word) {
        return word.toLowerCase(Locale.ROOT).startsWith("debit") ? "DEBIT" : "CREDIT";
    }

    private static BigDecimal amount(String s) {
        return new BigDecimal(s.replace(",", ""));
    }

    private static final List<DateTimeFormatter> DATES = List.of(
        DateTimeFormatter.ofPattern("dd-MM-uu"), DateTimeFormatter.ofPattern("dd-MM-uuuu"),
        DateTimeFormatter.ofPattern("dd/MM/uu"), DateTimeFormatter.ofPattern("dd/MM/uuuu"),
        DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH));

    static LocalDate date(String s) {
        String v = s.trim();
        if (v.matches("\\d{1,2}-[A-Za-z]{3}-\\d{4}")) v = v.substring(0, v.indexOf('-') + 2) + v.substring(v.indexOf('-') + 2).toLowerCase(Locale.ROOT);
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(v, f);
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return null;
    }

    /** "UPI/P2M/123456/SUNIL STORE" → "SUNIL STORE". */
    private static String lastSegment(String info) {
        int i = info.lastIndexOf('/');
        return i >= 0 && i < info.length() - 1 ? info.substring(i + 1).trim() : info.trim();
    }

    /** "HDFC Small Ca/5072/HA" → "HDFC Small Ca". */
    private static String firstSegment(String info) {
        int i = info.indexOf('/');
        return (i > 0 ? info.substring(0, i) : info).trim();
    }

    private static String last4(String masked) {
        String digits = masked.replaceAll("\\D", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

    /** "Mr. PRITAM KUMARSI" → "PRITAM KUMARSI"; drops a trailing "Avl Balance…" if it slipped in. */
    private static String cleanParty(String raw) {
        String s = raw.replaceAll("(?i)\\bAvl\\b.*$", "").trim();
        return s.replaceAll("(?i)^(mr|mrs|ms|miss|dr)\\.?\\s+", "").trim();
    }

    /**
     * Whether a counterparty is the account holder, allowing for banks cutting names short:
     * "PRITAM KUMARSI" and "Pritam Kumar" are both "PRITAM KUMAR SINGH". The first names must
     * be equal and the rest of one must start with the rest of the other.
     */
    public static boolean isHolder(String party, String holderName) {
        if (party == null || holderName == null) return false;
        String[] p = party.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").trim().split("\\s+");
        String[] h = holderName.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").trim().split("\\s+");
        if (p.length == 0 || h.length == 0 || p[0].length() < 3 || !p[0].equals(h[0])) return false;
        String pr = String.join("", java.util.Arrays.copyOfRange(p, 1, p.length));
        String hr = String.join("", java.util.Arrays.copyOfRange(h, 1, h.length));
        if (pr.isEmpty() || hr.isEmpty()) return pr.isEmpty() && hr.isEmpty();
        return pr.length() >= 3 && (hr.startsWith(pr) || pr.startsWith(hr));
    }

    // A rupee amount ("Rs 500", "INR 1,200", "₹99") or any figure with paise ("1,200.00 Dr").
    private static final Pattern AMOUNT = Pattern.compile(
        "(rs\\.?|inr|₹)\\s?\\d|\\d[\\d,]*\\.\\d{2}(?!\\d)", Pattern.CASE_INSENSITIVE);

    /**
     * Whether a message states any amount at all. A bank's offers, reminders and notices that
     * state none ("earn 7% p.a. on your FD") are recognised as no transaction without a model
     * call; anything with an amount still goes to the extractor, however it is worded.
     */
    public static boolean statesAmount(String text) {
        return text != null && AMOUNT.matcher(text).find();
    }

    /** Whether the sender is a bank (or card issuer) rather than a broker or fund house. */
    public static boolean isBank(String senderDomain) {
        if (senderDomain == null) return false;
        String issuer = com.marketai.document.classify.IssuerDomainRegistry.issuerFor(senderDomain).orElse("");
        return issuer.contains("Bank") || issuer.equals("SBI") || issuer.equals("Kotak") || issuer.equals("IDFC First");
    }

    /** Lists each template this class reads — for the sync report and tests. */
    public static List<String> templates() {
        List<String> out = new ArrayList<>();
        out.add("axis-alert");
        out.add("hdfc-alert");
        out.add("sbi-alert");
        out.add("sbi-hold");
        return out;
    }
}
