package com.marketai.gmail.parser;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads CDSL's "Transactions In Your Demat Account" notice: one row per security moved into or
 * out of the demat account, as
 * {@code 1 | LARSEN & TOUBRO LIMITED-EQUITY SHARES OF RS.2/- EACH | INE018A01030 | 4.000 | Credit | 30/09/2026 12:55:00}.
 * Nothing is booked from it — the contract notes book the trades — but each row is the
 * depository's own record that a trade settled, so it is kept to check the trades against.
 */
public final class DepositoryMovementParser {

    private DepositoryMovementParser() {}

    public record Movement(int rowNo, String companyName, String isin, BigDecimal quantity, boolean credit, LocalDateTime movedAt) {}

    public record Notice(String accountSuffix, List<Movement> movements) {}

    private static final Pattern ROW = Pattern.compile(
        "(?m)^\\s*(\\d+)\\s*\\|\\s*(.+?)\\s*\\|\\s*(IN[A-Z0-9]{9}[0-9])\\s*\\|\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*\\|\\s*(Credit|Debit)\\s*\\|\\s*(\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2})\\s*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_ROW = Pattern.compile("(?m)^\\s*\\d+\\s*\\|");
    private static final Pattern ACCOUNT = Pattern.compile("(?i)demat account ending with \\*?(\\d{4,})");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss");

    /** The rows of a notice, or null when the text is not one (no rows, or the account isn't named). */
    public static Notice parse(String text) {
        if (text == null) return null;
        Matcher a = ACCOUNT.matcher(text);
        if (!a.find()) return null;
        String digits = a.group(1);
        List<Movement> out = new ArrayList<>();
        Matcher m = ROW.matcher(text);
        while (m.find()) {
            try {
                out.add(new Movement(Integer.parseInt(m.group(1)), m.group(2).trim(), m.group(3).toUpperCase(),
                    new BigDecimal(m.group(4).replace(",", "")), m.group(5).equalsIgnoreCase("Credit"),
                    LocalDateTime.parse(m.group(6), AT)));
            } catch (Exception e) {
                return null;   // a row in the table that doesn't read is a layout this doesn't know
            }
        }
        // A numbered row that didn't read is a layout this doesn't know; part of a notice is not kept.
        int rows = 0;
        for (Matcher any = ANY_ROW.matcher(text); any.find(); ) rows++;
        if (rows != out.size()) return null;
        // Only the last four digits are kept: enough to tell two demat accounts apart.
        return out.isEmpty() ? null : new Notice(digits.substring(digits.length() - 4), out);
    }
}
