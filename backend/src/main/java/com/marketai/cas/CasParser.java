package com.marketai.cas;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the text of a CAMS / KFintech mutual-fund Consolidated Account Statement (detailed
 * version: every transaction, per folio and scheme). Pure text in, structure out, so it can be
 * tested without a PDF.
 *
 * <p>Layout it expects, per scheme:
 * <pre>
 * HDFC Mutual Fund
 * Folio No: 12345678 / 90   PAN: ABCDE1234F
 * CODE-Scheme Name - Direct Growth (Advisor: DIRECT)  ISIN: INF179K01XX0(Demat)  Registrar : CAMS
 * Opening Unit Balance: 1,000.000
 * 15-Jun-2021  SIP Purchase - Instalment No 1   5,000.00  50.123  99.7500  50.123
 * 20-Jul-2022  Redemption                       (2,000.00) (20.000) 100.00  30.123
 * Closing Unit Balance: 30.123   NAV on 31-Dec-2022: INR 120.50   Total Cost Value: ...
 * </pre>
 * Lines it can't place are returned in {@link Result#unparsed} — never silently dropped.
 * Every scheme carries an integrity check: opening units plus the signed units of its
 * transactions must equal the closing units the statement prints.
 */
public final class CasParser {

    private CasParser() {}

    public static final class Txn {
        public LocalDate date;
        public String description;
        /** BUY, SIP, SELL, REDEMPTION, SWITCH_IN, SWITCH_OUT, DIVIDEND, BONUS. */
        public String type;
        public BigDecimal amount;
        /** Signed as printed: redemptions are negative. Null for amount-only lines (dividend payouts). */
        public BigDecimal units;
        public BigDecimal nav;
    }

    public static final class Scheme {
        public String amc;
        public String folio;
        public String name;
        public String isin;
        public BigDecimal openingUnits = BigDecimal.ZERO;
        public BigDecimal closingUnits;
        public BigDecimal closingNav;
        public LocalDate navDate;
        public final List<Txn> txns = new ArrayList<>();

        /** Difference between printed closing units and opening + transactions; null if no closing balance was found. */
        public BigDecimal unitMismatch() {
            if (closingUnits == null) return null;
            BigDecimal sum = openingUnits;
            for (Txn t : txns) if (t.units != null) sum = sum.add(t.units);
            return closingUnits.subtract(sum);
        }
    }

    public static final class Result {
        public LocalDate periodFrom, periodTo;
        public final List<Scheme> schemes = new ArrayList<>();
        public final List<String> unparsed = new ArrayList<>();
    }

    private static final String NUM = "\\(?-?[\\d,]+(?:\\.\\d+)?\\)?";
    private static final String DATE = "\\d{2}-[A-Za-z]{3}-\\d{4}";
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private static final Pattern PERIOD = Pattern.compile("(" + DATE + ")\\s+[Tt]o\\s+(" + DATE + ")");
    private static final Pattern AMC = Pattern.compile("^(.{2,60}\\s(?:Mutual Fund|MUTUAL FUND))\\s*$");
    private static final Pattern FOLIO = Pattern.compile("Folio No:\\s*([0-9A-Za-z]+(?:\\s*/\\s*[0-9A-Za-z]+)?)");
    private static final Pattern SCHEME = Pattern.compile("^(?:[A-Za-z0-9]{2,12}\\s*-\\s*)?(.+?)\\s*(?:\\(Advisor:[^)]*\\))?\\s*ISIN:\\s*(INF[A-Z0-9]{9})");
    private static final Pattern OPENING = Pattern.compile("Opening Unit Balance:\\s*(" + NUM + ")");
    private static final Pattern CLOSING = Pattern.compile("Closing Unit Balance:\\s*(" + NUM + ")");
    private static final Pattern NAV_ON = Pattern.compile("NAV on (" + DATE + "):\\s*INR\\s*(" + NUM + ")");
    private static final Pattern FULL_TXN = Pattern.compile("^(" + DATE + ")\\s+(.*?)\\s+(" + NUM + ")\\s+(" + NUM + ")\\s+(" + NUM + ")\\s+(" + NUM + ")$");
    private static final Pattern AMOUNT_ONLY_TXN = Pattern.compile("^(" + DATE + ")\\s+(.*?)\\s+(" + NUM + ")$");
    private static final Pattern DATED_LINE = Pattern.compile("^" + DATE + "\\s+.*");

    public static Result parse(String text) {
        Result out = new Result();
        Scheme cur = null;
        String amc = null, folio = null;

        for (String raw : text.split("\\R")) {
            String line = raw.replace(' ', ' ').trim();
            if (line.isEmpty()) continue;

            if (out.periodFrom == null) {
                Matcher p = PERIOD.matcher(line);
                if (p.find()) { out.periodFrom = date(p.group(1)); out.periodTo = date(p.group(2)); }
            }
            Matcher m;
            if ((m = AMC.matcher(line)).matches()) { amc = m.group(1).trim(); continue; }
            if ((m = FOLIO.matcher(line)).find()) { folio = m.group(1).replaceAll("\\s+", ""); continue; }
            if (line.contains("ISIN:") && (m = SCHEME.matcher(line)).find()) {
                cur = new Scheme();
                cur.amc = amc;
                cur.folio = folio;
                cur.name = m.group(1).trim();
                cur.isin = m.group(2);
                out.schemes.add(cur);
                continue;
            }
            if (cur == null) continue;

            if ((m = OPENING.matcher(line)).find()) { cur.openingUnits = num(m.group(1)); continue; }
            if ((m = CLOSING.matcher(line)).find()) {
                cur.closingUnits = num(m.group(1));
                Matcher n = NAV_ON.matcher(line);
                if (n.find()) { cur.navDate = date(n.group(1)); cur.closingNav = num(n.group(2)); }
                continue;
            }
            if ((m = FULL_TXN.matcher(line)).matches()) {
                Txn t = new Txn();
                t.date = date(m.group(1));
                t.description = m.group(2).trim();
                t.amount = num(m.group(3)).abs();
                t.units = num(m.group(4));
                t.nav = num(m.group(5));
                t.type = typeOf(t.description, t.units);
                if (t.type == null) { out.unparsed.add(line); continue; }
                cur.txns.add(t);
                continue;
            }
            if (DATED_LINE.matcher(line).matches()) {
                if (line.contains("***")) continue; // stamp duty, STT: charges inside a purchase, not separate holdings events
                if ((m = AMOUNT_ONLY_TXN.matcher(line)).matches()) {
                    String desc = m.group(2).trim();
                    String lower = desc.toLowerCase(Locale.ROOT);
                    if (lower.contains("dividend") || lower.contains("idcw")) {
                        Txn t = new Txn();
                        t.date = date(m.group(1));
                        t.description = desc;
                        t.amount = num(m.group(3)).abs();
                        t.type = "DIVIDEND";
                        cur.txns.add(t);
                        continue;
                    }
                }
                out.unparsed.add(line);
            }
        }
        return out;
    }

    static String typeOf(String description, BigDecimal units) {
        String d = description.toLowerCase(Locale.ROOT);
        if (d.contains("switch") && d.contains("out")) return "SWITCH_OUT";
        if (d.contains("switch") && d.contains("in")) return "SWITCH_IN";
        if (d.contains("redemption") || d.contains("redeem")) return "SELL";
        if (d.contains("bonus")) return "BONUS";
        if (d.contains("sip") || d.contains("systematic") || d.contains("instalment")) return "SIP";
        if (d.contains("purchase") || d.contains("reinvest") || d.contains("subscription")) return "BUY";
        // Anything else that moved units: direction from the sign printed on the units column.
        if (units.signum() < 0) return null;
        return null;
    }

    private static LocalDate date(String s) { return LocalDate.parse(s, DMY); }

    private static BigDecimal num(String s) {
        boolean neg = s.startsWith("(") || s.startsWith("-");
        BigDecimal v = new BigDecimal(s.replaceAll("[(),\\s-]", ""));
        return neg ? v.negate() : v;
    }
}
