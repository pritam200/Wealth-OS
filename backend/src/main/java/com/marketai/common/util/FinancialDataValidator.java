package com.marketai.common.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared "is this real data or a parsing artifact" checks used before any financial value
 * (a fund name, a stock symbol) is persisted. Centralized here so every import path — email
 * parsers, PDF statement parsing, AI extraction — enforces the same rule: unverifiable data
 * must never silently become a holding.
 */
public final class FinancialDataValidator {

    private FinancialDataValidator() {}

    // Column-header / boilerplate text that a table-parsing bug can mistake for an actual
    // scheme name (e.g. a multi-fund "Statement of Account" PDF parsed with single-SIP logic
    // ends up capturing the table header "Name ... Cost of Investment ..." as if it were the
    // fund itself).
    private static final Set<String> UNVERIFIABLE_FUND_NAME_TOKENS = new HashSet<>(Arrays.asList(
        "name", "scheme name", "scheme", "folio", "folio no", "folio number",
        "cost of investment", "cost", "units", "units balance", "unit balance",
        "nav", "market value", "value", "amount", "date", "total", "particulars",
        "description", "closing balance", "opening balance", "transaction type"
    ));

    private static final Set<String> HEADER_WORDS = new HashSet<>(Arrays.asList(
        "name", "scheme", "folio", "no", "number", "cost", "of", "investment",
        "units", "balance", "nav", "market", "value", "amount", "date", "total"
    ));

    /** True if the extracted "fund name" is boilerplate/header text rather than a real scheme name. */
    public static boolean looksLikeUnverifiableFundName(String fundName) {
        if (fundName == null) return false;
        String normalized = fundName.trim().toLowerCase().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return true;
        if (UNVERIFIABLE_FUND_NAME_TOKENS.contains(normalized)) return true;
        // Reject names built entirely out of header words strung together
        // (e.g. "Name Cost of Investment").
        String[] words = normalized.split("\\s+");
        if (words.length == 0) return false;
        for (String w : words) {
            if (!HEADER_WORDS.contains(w)) return false;
        }
        return true;
    }

    // Broker client codes look like "MA7468533" — 1-3 letters followed by 5+ digits. Real
    // NSE/BSE equity symbols are never shaped like this.
    private static final Pattern CLIENT_CODE_PATTERN = Pattern.compile("^[A-Z]{1,3}\\d{5,}$");

    /** True if the candidate looks like a broker client/account code rather than a stock ticker. */
    public static boolean looksLikeClientCode(String candidate) {
        return candidate != null && CLIENT_CODE_PATTERN.matcher(candidate).matches();
    }
}
