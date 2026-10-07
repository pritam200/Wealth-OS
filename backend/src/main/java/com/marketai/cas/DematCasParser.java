package com.marketai.cas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the text of an NSDL / CDSL demat Consolidated Account Statement for what it reliably
 * carries: the securities held, their quantity and value, per demat account. It does NOT carry
 * purchase prices, so this yields positions, not cost basis (cost comes from broker exports).
 *
 * <p>Tolerant by design: a holding is any line that starts with an ISIN followed by a name and
 * numbers. Quantity is the first number after the name, value the last, price the one before it.
 * A line whose quantity x price doesn't match its value is flagged, and ISIN lines that can't be
 * read at all are returned rather than dropped.
 */
public final class DematCasParser {

    private DematCasParser() {}

    public static final class Holding {
        public String account;      // "<DP name> · DP <id> · Client <id>" or whatever identifies the demat account
        public String isin;
        public String name;
        public BigDecimal quantity;
        public BigDecimal price;    // may be null when the line carries only quantity and value
        public BigDecimal value;
        public boolean valueChecks = true;
    }

    public static final class Result {
        public LocalDate asOf;
        public final List<Holding> holdings = new ArrayList<>();
        public final List<String> unparsed = new ArrayList<>();
    }

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final Pattern AS_ON = Pattern.compile("(?i)(?:as on|as of|period[^\\n]*?to)\\s*:?\\s*(\\d{2}-[A-Za-z]{3}-\\d{4})");
    private static final Pattern DP_CLIENT = Pattern.compile("(?i)DP\\s*ID\\s*:?\\s*([A-Z0-9]{8})\\s*(?:and|&|,|\\s)\\s*Client\\s*ID\\s*:?\\s*(\\d{6,8})");
    private static final Pattern CDSL_ACCT = Pattern.compile("(?i)(?:CDSL\\s*)?(?:Demat\\s*)?(?:BO|Client)\\s*ID\\s*:?\\s*(\\d{16})");
    private static final Pattern ISIN_LINE = Pattern.compile("^(IN[EF9][A-Z0-9]{9})\\s+(.*)$");
    private static final Pattern NUMBER = Pattern.compile("^-?[\\d,]+(?:\\.\\d+)?$");

    public static boolean looksLikeDemat(String text) {
        String head = (text.length() > 6000 ? text.substring(0, 6000) : text).toUpperCase(Locale.ROOT);
        return (head.contains("NSDL") || head.contains("CDSL")) && (head.contains("DP ID") || head.contains("DEMAT") || head.contains("BO ID"));
    }

    public static Result parse(String text) {
        Result out = new Result();
        String account = "Demat account";
        for (String raw : text.split("\\R")) {
            String line = raw.replace('\u00a0', ' ').trim();
            if (line.isEmpty()) continue;

            if (out.asOf == null) {
                Matcher a = AS_ON.matcher(line);
                if (a.find()) out.asOf = LocalDate.parse(a.group(1), DMY);
            }
            Matcher m = DP_CLIENT.matcher(line);
            if (m.find()) { account = "DP " + m.group(1) + " / Client " + m.group(2); continue; }
            m = CDSL_ACCT.matcher(line);
            if (m.find()) { account = "CDSL BO " + m.group(1); continue; }

            m = ISIN_LINE.matcher(line);
            if (!m.matches()) continue;

            String rest = m.group(2);
            String[] tok = rest.split("\\s+");
            // numbers = the trailing run of numeric tokens; name = everything before it (a name may
            // itself contain a number, e.g. "HDFC TOP 100 FUND")
            int firstNum = tok.length;
            while (firstNum > 0 && NUMBER.matcher(tok[firstNum - 1]).matches()) firstNum--;
            if (firstNum <= 0 || firstNum >= tok.length) { out.unparsed.add(line); continue; }
            List<BigDecimal> nums = new ArrayList<>();
            for (int i = firstNum; i < tok.length; i++) nums.add(new BigDecimal(tok[i].replace(",", "")));
            if (nums.size() < 2) { out.unparsed.add(line); continue; }

            Holding h = new Holding();
            h.account = account;
            h.isin = m.group(1);
            h.name = String.join(" ", java.util.Arrays.copyOfRange(tok, 0, firstNum));
            h.quantity = nums.get(0);
            h.value = nums.get(nums.size() - 1);
            if (nums.size() >= 3) {
                h.price = nums.get(nums.size() - 2);
                BigDecimal expected = h.quantity.multiply(h.price);
                BigDecimal tolerance = h.value.abs().multiply(new BigDecimal("0.01")).max(new BigDecimal("1"));
                h.valueChecks = expected.subtract(h.value).abs().compareTo(tolerance) <= 0;
            } else if (h.quantity.signum() > 0) {
                h.price = h.value.divide(h.quantity, 4, RoundingMode.HALF_UP);
            }
            out.holdings.add(h);
        }
        return out;
    }
}
