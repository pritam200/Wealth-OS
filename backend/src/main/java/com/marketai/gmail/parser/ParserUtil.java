package com.marketai.gmail.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParserUtil {

    private static final List<DateTimeFormatter> DATE_FMTS = Arrays.asList(
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd-MMM-yyyy"),
        DateTimeFormatter.ofPattern("dd MMM yyyy"),
        DateTimeFormatter.ofPattern("MMM dd, yyyy"),
        DateTimeFormatter.ofPattern("d-MMM-yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy")
    );

    public static String findFirst(String text, Pattern p) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    public static List<String> findAll(String text, Pattern pattern) {
        List<String> results = new ArrayList<>();
        if (text == null) return results;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            results.add(m.group(1));
        }
        return results;
    }

    public static BigDecimal parseMoney(String raw) {
        if (raw == null) return null;
        try { return new BigDecimal(raw.replace(",", "").trim()); } catch (Exception e) { return null; }
    }

    public static Integer parseQty(String raw) {
        if (raw == null) return null;
        try { return Integer.parseInt(raw.replace(",", "").trim()); } catch (Exception e) { return null; }
    }

    public static LocalDate parseDate(String raw) {
        if (raw == null) return null;
        for (DateTimeFormatter fmt : DATE_FMTS) {
            try { return LocalDate.parse(raw.trim(), fmt); } catch (Exception ignored) {}
        }
        return null;
    }

    public static boolean containsIgnoreCase(String text, String... keywords) {
        for (String kw : keywords) {
            if (text != null && text.toLowerCase().contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    public static int parseIntSafe(String s, int defaultVal) {
        if (s == null) return defaultVal;
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return defaultVal; }
    }
}
