package com.marketai.reconciliation.service;

import com.marketai.dataplatform.service.ExcelCsvConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A broker's holdings statement — m.Stock's "Portfolio Statement" export — read as the shares it
 * says are held: one row per scrip with its quantity and average buy price. Only the EQUITY table
 * is read; the fund table after it is left alone. Every row must agree with itself (quantity times
 * average is the invested value) and the rows must add up to the statement's TOTAL, or the file is
 * refused: a statement read wrongly here would replace good holdings with bad ones.
 */
public final class BrokerHoldingsStatement {

    private BrokerHoldingsStatement() {}

    public record Row(String symbol, BigDecimal quantity, BigDecimal averagePrice, BigDecimal previousClose) {
        public BigDecimal invested() { return quantity.multiply(averagePrice).setScale(2, RoundingMode.HALF_UP); }
    }

    /** The rows, or an exception saying why the file can't be trusted. */
    public static List<Row> parse(byte[] xlsx) {
        return parseCsv(ExcelCsvConverter.toCsv(xlsx));
    }

    static List<Row> parseCsv(String csv) {
        List<List<String>> lines = new ArrayList<>();
        for (String line : csv.split("\r?\n")) lines.add(cells(line));

        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!first(lines.get(i)).equalsIgnoreCase("Scrip Name")) continue;
            // The equity table: the section heading above it says EQUITY.
            for (int j = i - 1; j >= 0 && j >= i - 2; j--) {
                if (first(lines.get(j)).equalsIgnoreCase("EQUITY")) { header = i; break; }
            }
            if (header >= 0) break;
        }
        if (header < 0) throw new IllegalArgumentException("No EQUITY table with a \"Scrip Name\" header was found in this file.");
        List<String> h = lines.get(header);
        int qty = col(h, "Total Qty"), avg = col(h, "Avg. Buy Price"), inv = col(h, "Invested Value"), close = col(h, "Previous Close");

        List<Row> rows = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal stated = null;
        for (int i = header + 1; i < lines.size(); i++) {
            List<String> r = lines.get(i);
            String name = first(r);
            if (name.isEmpty()) continue;   // a spacer row before the TOTAL
            if (name.equalsIgnoreCase("Scrip Name") || name.equalsIgnoreCase("MUTUAL FUND")) break;   // the next table: no TOTAL seen
            if (name.equalsIgnoreCase("TOTAL")) {
                stated = number(cell(r, inv), "the TOTAL invested value");
                break;
            }
            BigDecimal q = number(cell(r, qty), name + "'s quantity");
            BigDecimal a = number(cell(r, avg), name + "'s average price");
            BigDecimal v = number(cell(r, inv), name + "'s invested value");
            BigDecimal c = close < 0 || cell(r, close).isBlank() ? null : number(cell(r, close), name + "'s previous close");
            if (q.signum() < 0 || a.signum() < 0) throw new IllegalArgumentException(name + " has a negative quantity or price.");
            if (q.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException(name + " has a part share (" + q.toPlainString() + ").");
            BigDecimal expect = q.multiply(a);
            if (expect.subtract(v).abs().compareTo(BigDecimal.ONE.max(v.abs().multiply(new BigDecimal("0.001")))) > 0) {
                throw new IllegalArgumentException(name + ": " + q.toPlainString() + " × " + a.toPlainString() + " is not the invested value "
                    + v.toPlainString() + " the statement gives.");
            }
            String symbol = name.trim().toUpperCase(Locale.ROOT);
            if (!symbol.matches("[A-Z0-9&\\-]{1,20}")) throw new IllegalArgumentException("\"" + name + "\" is not a scrip code.");
            rows.add(new Row(symbol, q, a, c));
            sum = sum.add(v);
        }
        if (stated == null) throw new IllegalArgumentException("The EQUITY table has no TOTAL row to check the rows against.");
        if (sum.subtract(stated).abs().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("The rows add up to ₹" + sum.toPlainString() + ", not the ₹" + stated.toPlainString()
                + " TOTAL the statement gives.");
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("The EQUITY table is empty.");
        return rows;
    }

    private static int col(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) if (header.get(i).trim().equalsIgnoreCase(name)) return i;
        if (name.equals("Previous Close")) return -1;
        throw new IllegalArgumentException("The EQUITY table has no \"" + name + "\" column.");
    }

    private static String first(List<String> r) {
        return r.isEmpty() ? "" : r.get(0).trim();
    }

    private static String cell(List<String> r, int i) {
        return i < 0 || i >= r.size() ? "" : r.get(i).trim();
    }

    private static BigDecimal number(String s, String what) {
        try {
            return new BigDecimal(s.replace(",", "").trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read " + what + " (\"" + s + "\").");
        }
    }

    /** One CSV line as ExcelCsvConverter writes it: comma-separated, quoted with doubled quotes. */
    static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cur.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }
}
