package com.marketai.card.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a card statement's own lines for what the cardholder should look at: charges the bank
 * levied (late fee, interest, annual fee, GST on fees...) and activity that stands out (a
 * possible double charge, a very large purchase, a foreign-currency or cash transaction).
 * Pure and deterministic: it flags, it never decides that anything is wrong.
 */
public final class CardStatementAnalyzer {

    public static final String ALERT = "ALERT", WARN = "WARN", INFO = "INFO";

    /** One statement line, as extracted. {@code debit} is false for payments and refunds. */
    public record Line(String merchant, BigDecimal amount, LocalDate date, String currency, boolean debit, String evidence) {}

    public record Flag(String type, String severity, String title, String detail, BigDecimal amount) {}

    private static final BigDecimal LARGE_FLOOR = new BigDecimal("50000");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Rule(String type, String severity, String title, Pattern pattern) {
        Rule(String type, String severity, String title, String regex) {
            this(type, severity, title, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
    }

    /** First match wins, so the specific charges come before the catch-alls. */
    private static final List<Rule> CHARGES = List.of(
        new Rule("LATE_FEE", ALERT, "Late payment fee", "late\\s*(payment|pymt)?\\s*(fee|charge)|delayed payment|late fee"),
        new Rule("OVERLIMIT_FEE", ALERT, "Over-limit fee", "over\\s*-?\\s*limit"),
        new Rule("INTEREST", ALERT, "Interest / finance charge",
            "finance\\s*(charge|chrg|chg)|interest\\s*(charge|on)|retail interest|revolving|fin\\.?\\s*chg"),
        new Rule("ANNUAL_FEE", WARN, "Annual / membership fee", "annual fee|membership fee|joining fee|renewal fee"),
        new Rule("CASH_ADVANCE_FEE", WARN, "Cash advance fee", "cash\\s*(advance|adv)\\s*(fee|charge)"),
        new Rule("FOREX_MARKUP", WARN, "Foreign currency markup", "forex|mark\\s*-?\\s*up|cross\\s*currency|currency conversion"),
        new Rule("EMI_FEE", WARN, "EMI / processing fee", "processing fee|emi (fee|charge)|foreclosure"),
        new Rule("GST", INFO, "GST on charges", "\\b(i|c|s)?gst\\b"),
        new Rule("OTHER_FEE", WARN, "Other bank charge", "\\bfees?\\b|\\bcharges?\\b|surcharge|convenience"));

    private static final Pattern CASH = Pattern.compile("cash withdrawal|atm|cash advance", Pattern.CASE_INSENSITIVE);

    private CardStatementAnalyzer() {}

    public static List<Flag> analyze(List<Line> lines, BigDecimal creditLimit, BigDecimal totalDue) {
        List<Flag> flags = new ArrayList<>();
        List<Line> debits = lines.stream().filter(l -> l.debit() && l.amount() != null && l.amount().signum() > 0).toList();

        // Bank charges: each line the bank itself levied.
        for (Line l : debits) {
            String text = ((l.merchant() == null ? "" : l.merchant()) + " " + (l.evidence() == null ? "" : l.evidence()));
            // The merchant decides; evidence is a fallback only when no merchant was given, since
            // a line's evidence repeats the whole row (a shop named "... Charges Pvt Ltd" is a purchase).
            String basis = l.merchant() != null && !l.merchant().isBlank() ? l.merchant() : text;
            for (Rule r : CHARGES) {
                if (r.pattern().matcher(basis).find()) {
                    flags.add(new Flag(r.type(), r.severity(), r.title(), describe(l), l.amount()));
                    break;
                }
            }
        }

        // Possible double charge: the same merchant, amount and day twice.
        Map<String, List<Line>> same = new HashMap<>();
        for (Line l : debits) {
            if (l.date() == null || l.merchant() == null) continue;
            same.computeIfAbsent(l.merchant().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") + "|"
                + l.amount().stripTrailingZeros().toPlainString() + "|" + l.date(), k -> new ArrayList<>()).add(l);
        }
        for (List<Line> g : same.values()) {
            if (g.size() > 1 && !isCharge(g.get(0))) {
                Line l = g.get(0);
                flags.add(new Flag("DUPLICATE_CHARGE", ALERT, "Possible double charge",
                    g.size() + " identical charges of ₹" + plain(l.amount()) + " at " + l.merchant() + " on " + l.date()
                        + " — check whether both are real.", l.amount()));
            }
        }

        // Large purchase: well above this statement's own norm, or a big share of the limit.
        List<BigDecimal> sorted = debits.stream().filter(l -> !isCharge(l)).map(Line::amount).sorted().toList();
        BigDecimal median = sorted.isEmpty() ? BigDecimal.ZERO : sorted.get(sorted.size() / 2);
        for (Line l : debits) {
            if (isCharge(l)) continue;
            boolean aboveNorm = sorted.size() >= 4 && l.amount().compareTo(median.multiply(BigDecimal.valueOf(5))) >= 0
                && l.amount().compareTo(LARGE_FLOOR) >= 0;
            boolean bigShare = creditLimit != null && creditLimit.signum() > 0
                && l.amount().compareTo(creditLimit.multiply(new BigDecimal("0.4"))) >= 0;
            if (aboveNorm || bigShare) {
                flags.add(new Flag("LARGE_TRANSACTION", WARN, "Unusually large purchase", describe(l), l.amount()));
            }
        }

        // Foreign currency and cash.
        for (Line l : debits) {
            if (l.currency() != null && !l.currency().isBlank() && !"INR".equalsIgnoreCase(l.currency())) {
                flags.add(new Flag("FOREIGN_CURRENCY", WARN, "Foreign-currency transaction",
                    describe(l) + " (" + l.currency().toUpperCase(Locale.ROOT) + ") — expect a markup and GST.", l.amount()));
            } else if (!isCharge(l) && l.merchant() != null && CASH.matcher(l.merchant()).find()) {
                flags.add(new Flag("CASH_ADVANCE", ALERT, "Cash withdrawal on card",
                    describe(l) + " — interest starts the day it is withdrawn.", l.amount()));
            }
        }

        // Statement-level: balance past the limit.
        if (creditLimit != null && totalDue != null && creditLimit.signum() > 0 && totalDue.compareTo(creditLimit) > 0) {
            flags.add(new Flag("OVER_LIMIT", ALERT, "Over the credit limit",
                "Total due ₹" + plain(totalDue) + " is above the ₹" + plain(creditLimit) + " limit.", totalDue));
        }

        flags.sort(Comparator.comparingInt((Flag f) -> rank(f.severity())));
        return flags;
    }

    private static boolean isCharge(Line l) {
        String m = l.merchant() == null ? "" : l.merchant();
        return CHARGES.stream().anyMatch(r -> r.pattern().matcher(m).find());
    }

    private static int rank(String severity) {
        return ALERT.equals(severity) ? 0 : WARN.equals(severity) ? 1 : 2;
    }

    private static String describe(Line l) {
        return (l.merchant() != null ? l.merchant() : "Charge") + " ₹" + plain(l.amount())
            + (l.date() != null ? " on " + l.date() : "");
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** A stored statement's flags, with a statement whose own figures do not add up flagged first. */
    public static List<Flag> flagsFor(com.marketai.card.entity.CardStatement s) {
        List<Flag> flags = new ArrayList<>(fromJson(s.getFlagsJson()));
        if (Boolean.TRUE.equals(s.getArithmeticMismatch())) {
            // A misread number, or something the bank got wrong.
            flags.add(0, new Flag("ARITHMETIC_MISMATCH", ALERT, "Statement figures do not add up",
                s.getArithmeticMismatchDetail(), null));
        }
        return flags;
    }

    /** Charges the bank itself levied, as opposed to activity that merely stands out. */
    public static boolean isBankCharge(Flag f) {
        return CHARGES.stream().anyMatch(r -> r.type().equals(f.type()));
    }

    public static String toJson(List<Flag> flags) {
        try {
            List<Flag> kept = new ArrayList<>(flags);
            String s = MAPPER.writeValueAsString(kept);
            // Flags are most serious first, so what is dropped to fit the column is the least serious.
            while (s.length() > 4000 && !kept.isEmpty()) {
                kept.remove(kept.size() - 1);
                s = MAPPER.writeValueAsString(kept);
            }
            return s;
        } catch (Exception e) {
            return "[]";
        }
    }

    public static List<Flag> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Flag>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
