package com.marketai.gmail.parser;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses dividend credit emails from companies, registrars (Link Intime, KFintech,
 * CAMS), depositories (NSDL/CDSL) and brokers. Records them as DIVIDEND income.
 */
@Component
@Order(1)
public class DividendParser implements EmailParser {

    private static final Pattern AMOUNT = Pattern.compile(
        "dividend\\s+(?:of\\s+|amount\\s+(?:of\\s+)?)?(?:Rs\\.?\\s*|INR\\s*|₹\\s*)([\\d,]+\\.?\\d*)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_ALT = Pattern.compile(
        "(?:credited|paid|received)[^\\d]{0,40}(?:Rs\\.?\\s*|INR\\s*|₹\\s*)([\\d,]+\\.?\\d*)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPANY = Pattern.compile(
        "(?:dividend\\s+(?:from|by|for|of)\\s+|from\\s+|for\\s+)([A-Z][A-Za-z&.\\- ]{2,50}?(?:Ltd|Limited|Industries|Corporation|Corp|Bank|Motors|Finance|Pharma|Tech|Infra|Energy|Power|Cement|Steel|Chemicals|Insurance|Solutions|Services|Technologies|Enterprises|Holdings|Capital|Securities|Textile|Telecom))",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPANY_ALT = Pattern.compile(
        "(?:shares?\\s+of\\s+|scrip\\s+|equity\\s+of\\s+|in\\s+respect\\s+of\\s+)([A-Z][A-Za-z&.\\- ]{2,50}?(?:Ltd|Limited|Industries|Corporation|Corp|Bank|Motors|Finance|Pharma|Tech|Infra|Energy|Power|Cement|Steel|Chemicals|Insurance|Solutions|Services|Technologies|Enterprises|Holdings|Capital|Securities|Textile|Telecom))",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile(
        "(?:on|dated|date)[:\\s]+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{4})",
        Pattern.CASE_INSENSITIVE);

    @Override
    public boolean canParse(String from, String subject) {
        boolean dividendSender = ParserUtil.containsIgnoreCase(from,
            "linkintime", "kfintech", "cams", "nsdl", "cdsl", "registrar", "integratedindia", "bigshareonline",
            "zerodha", "groww", "angelone", "angel broking", "upstox", "icicidirect", "mstock", "kotak securities",
            "hdfcsec", "motilal", "sharekhan");
        boolean dividendSubject = ParserUtil.containsIgnoreCase(subject,
            "dividend", "interim dividend", "final dividend", "dividend credited", "dividend payment",
            "dividend payout", "dividend received", "dividend income");
        return dividendSubject || (dividendSender && ParserUtil.containsIgnoreCase(subject, "dividend", "payout"));
    }

    @Override
    public List<ParsedEmail> parse(String from, String subject, String bodyText) {
        List<ParsedEmail> results = new ArrayList<>();
        String text = (subject != null ? subject : "") + "\n" + (bodyText != null ? bodyText : "");

        String amountStr = ParserUtil.findFirst(text, AMOUNT);
        if (amountStr == null) amountStr = ParserUtil.findFirst(text, AMOUNT_ALT);
        BigDecimal amount = ParserUtil.parseMoney(amountStr);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return results;

        String company = null;
        Matcher m = COMPANY.matcher(text);
        if (m.find()) company = m.group(1).trim();
        if (company == null) {
            m = COMPANY_ALT.matcher(text);
            if (m.find()) company = m.group(1).trim();
        }
        if (company != null) {
            company = company.replaceAll("\\s+(Ltd\\.?|Limited)$", " Ltd").trim();
        }
        String shortName = company != null ? toShortName(company) : null;

        String dateStr = ParserUtil.findFirst(text, DATE);
        LocalDate date = ParserUtil.parseDate(dateStr);

        String desc = shortName != null ? shortName + " Dividend" : "Dividend";
        results.add(ParsedEmail.builder()
            .type(ParsedEmail.Type.DIVIDEND)
            .symbol(company)
            .merchant(company)
            .amount(amount)
            .tradeDate(date != null ? date : LocalDate.now())
            .sourceDescription(String.format("%s: ₹%.0f", desc, amount))
            .build());
        return results;
    }

    private static String toShortName(String fullName) {
        if (fullName == null) return null;
        return fullName
            .replaceAll("(?i)\\s*(Limited|Ltd\\.?|Industries|Corporation|Corp|Enterprises|Holdings|Solutions|Services|Technologies)\\s*", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
