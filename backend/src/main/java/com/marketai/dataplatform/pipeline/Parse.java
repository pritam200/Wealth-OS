package com.marketai.dataplatform.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketai.dataplatform.domain.AssetClass;
import com.marketai.dataplatform.domain.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Locale;

/** Tolerant field readers shared by the normalisers. Absent or blank is null; malformed throws. */
final class Parse {
    private Parse() {}

    static String text(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText().trim();
        }
        return null;
    }

    static BigDecimal decimal(JsonNode n, String... names) {
        String s = text(n, names);
        return s == null ? null : decimal(s, names[0]);
    }

    static BigDecimal decimal(String s, String field) {
        try {
            return new BigDecimal(s.replace(",", "").replace("₹", "").replace("Rs.", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + field + "' is not a number");
        }
    }

    static LocalDate date(JsonNode n, String... names) {
        String s = text(n, names);
        return s == null ? null : date(s, names[0]);
    }

    static LocalDate date(String s, String field) {
        try {
            if (s.length() > 10 && s.contains("T")) {
                try { return OffsetDateTime.parse(s).toLocalDate(); } catch (Exception ignored) { return LocalDateTime.parse(s).toLocalDate(); }
            }
            if (s.matches("\\d{2}[-/]\\d{2}[-/]\\d{4}")) {
                String[] p = s.split("[-/]");
                return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            }
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("'" + field + "' is not a date");
        }
    }

    static LocalDateTime timestamp(JsonNode n, String... names) {
        String s = text(n, names);
        if (s == null) return null;
        try { return OffsetDateTime.parse(s).toLocalDateTime(); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(s); } catch (Exception ignored) { }
        try { return LocalDate.parse(s).atStartOfDay(); } catch (Exception e) { return null; }
    }

    static TransactionType type(String raw) {
        if (raw == null) return null;
        String k = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try { return TransactionType.valueOf(k); } catch (IllegalArgumentException ignored) { }
        return switch (k) {
            case "PURCHASE", "BUY_ORDER", "ADDITIONAL_PURCHASE", "LUMPSUM", "SYSTEMATIC_INVESTMENT", "B" -> TransactionType.BUY;
            case "SALE", "S", "SELL_ORDER" -> TransactionType.SELL;
            case "REDEEM", "REDEMPTION_PAYOUT" -> TransactionType.REDEMPTION;
            case "SYSTEMATIC_INVESTMENT_PLAN" -> TransactionType.SIP;
            case "SWITCH_IN", "SWITCHIN", "SWITCH_IN_(MERGER)" -> TransactionType.SWITCH_IN;
            case "SWITCH_OUT", "SWITCHOUT" -> TransactionType.SWITCH_OUT;
            case "DIVIDEND_PAYOUT", "IDCW" -> TransactionType.DIVIDEND;
            case "DIVIDEND_REINVESTMENT", "IDCW_REINVESTMENT", "REINVEST" -> TransactionType.BUY;
            case "CR", "CREDITED" -> TransactionType.CREDIT;
            case "DR", "DEBITED" -> TransactionType.DEBIT;
            default -> null;
        };
    }

    static AssetClass assetClass(String raw, AssetClass fallback) {
        if (raw == null) return fallback;
        String k = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try { return AssetClass.valueOf(k); } catch (IllegalArgumentException ignored) { }
        return switch (k) {
            case "MUTUAL_FUNDS", "MF", "MUTUALFUND" -> AssetClass.MUTUAL_FUND;
            case "EQUITIES", "EQUITY", "SHARES" -> AssetClass.STOCK;
            case "DEPOSIT", "SAVINGS", "CURRENT", "BANK" -> AssetClass.CASH;
            case "TERM_DEPOSIT", "FIXED_DEPOSIT" -> AssetClass.FD;
            case "RECURRING_DEPOSIT" -> AssetClass.RD;
            default -> fallback;
        };
    }
}
