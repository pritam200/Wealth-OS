package com.marketai.gmail.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared logic for parsing SEBI-mandated contract note PDFs after PDFTextStripper
 * extraction. All Indian brokers use the same tabular format (symbol, B/S, qty,
 * rate, amount columns), so this is reusable across Zerodha, Groww, MStock, etc.
 */
public final class ContractNoteHelper {

    private static final Logger log = LoggerFactory.getLogger(ContractNoteHelper.class);

    private ContractNoteHelper() {}

    private static final Pattern CN_DATE = Pattern.compile(
        "(?:Contract\\s*Note.*?|Trade\\s*Date[:\\s]*|Settlement\\s*Date[:\\s]*|Date[:\\s]+|" +
        "\\bfor\\s+(?:the\\s+)?(?:trade\\s+)?(?:date\\s+)?)" +
        "(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern CN_SIDE = Pattern.compile("(?:^|\\s|\\d)(B|S|BUY|SELL)(?:\\s|\\d|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CN_SYM = Pattern.compile("\\b([A-Z][A-Z0-9&-]{1,19})\\b");
    private static final Pattern CN_DECIMAL = Pattern.compile("\\b(\\d[\\d,]*\\.\\d{2,})\\b");
    private static final Pattern CN_INTEGER = Pattern.compile("\\b(\\d[\\d,]*)\\b");

    private static final Set<String> SKIP_WORDS = new HashSet<>(Arrays.asList(
        "NSE", "BSE", "EQ", "INE", "ISIN", "NET", "STT", "GST", "CGST", "SGST", "IGST",
        "TOTAL", "TRADE", "ORDER", "BUY", "SELL", "RATE", "QTY", "AMT", "SR", "NO",
        "CONTRACT", "NOTE", "SETTLEMENT", "BROKERAGE", "TURNOVER", "STAMP", "DUTY",
        "SECURITIES", "EXCHANGE", "CLEARING", "CLIENT", "CODE", "PAN", "DATE", "TIME",
        "GROSS", "PER", "UNIT", "CLOSING", "AMOUNT", "CHARGES", "TAX", "DESCRIPTION",
        "PARTICULARS", "DR", "CR", "REF", "FOR", "THE", "AND", "LTD", "LIMITED",
        "MEMBER", "REGISTERED", "OFFICE", "PHONE", "EMAIL", "FAX", "PAGE", "OF",
        "MARGIN", "DEBIT", "CREDIT", "BALANCE", "BILL", "NUMBER", "SEGMENT",
        "INDIA", "PVT", "PRIVATE", "CAPITAL", "MARKETS", "FINANCIAL", "SERVICES"
    ));

    /**
     * Parse contract note PDF text line-by-line to extract individual trades.
     *
     * @param text        full text extracted by PDFTextStripper
     * @param subject     email subject (for date/type inference fallback)
     * @param brokerLabel human-readable broker name for sourceDescription (e.g. "MStock", "Zerodha")
     * @return list of parsed trades; empty if no trades found
     */
    public static List<ParsedEmail> parseContractNoteRows(String text, String subject, String brokerLabel) {
        List<ParsedEmail> results = new ArrayList<>();
        if (text == null || text.isEmpty()) return results;

        LocalDate date = extractDate(text, subject);
        String exchange = text.contains("BSE") && !text.contains("NSE") ? "BSE" : "NSE";
        log.info("CN PARSE — broker={}, date={}, exchange={}, textLen={}", brokerLabel, date, exchange, text.length());

        String[] lines = text.split("\\n");
        int lineNum = 0;
        for (String line : lines) {
            lineNum++;
            String trimmed = line.trim();
            if (trimmed.length() < 10) continue;
            if (looksLikeHeader(trimmed)) {
                log.debug("CN SKIP HEADER line {}: [{}]", lineNum, truncateLog(trimmed));
                continue;
            }

            Matcher sideMatcher = CN_SIDE.matcher(trimmed);
            if (!sideMatcher.find()) continue;

            String sideStr = sideMatcher.group(1).toUpperCase();
            boolean isSell = "S".equals(sideStr) || "SELL".equals(sideStr);
            log.info("CN SIDE FOUND line {}: side={}, line=[{}]", lineNum, sideStr, truncateLog(trimmed));

            String beforeSide = trimmed.substring(0, sideMatcher.start());
            String symbol = findSymbol(beforeSide);
            if (symbol == null) symbol = findSymbol(trimmed);
            if (symbol == null) {
                log.info("CN NO SYMBOL line {}: beforeSide=[{}]", lineNum, truncateLog(beforeSide));
                continue;
            }

            String afterSide = trimmed.substring(sideMatcher.end());

            List<BigDecimal> decimals = new ArrayList<>();
            Matcher decMatcher = CN_DECIMAL.matcher(afterSide);
            while (decMatcher.find()) {
                BigDecimal val = ParserUtil.parseMoney(decMatcher.group(1));
                if (val != null) decimals.add(val);
            }

            List<Integer> integers = new ArrayList<>();
            Matcher intMatcher = CN_INTEGER.matcher(afterSide);
            while (intMatcher.find()) {
                String raw = intMatcher.group(1);
                if (!raw.contains(".")) {
                    Integer val = ParserUtil.parseQty(raw);
                    if (val != null && val > 0) integers.add(val);
                }
            }

            Integer qty = null;
            BigDecimal price = null;

            if (!integers.isEmpty() && !decimals.isEmpty()) {
                qty = integers.get(0);
                price = decimals.get(0);
            } else if (decimals.size() >= 2) {
                BigDecimal first = decimals.get(0);
                if (first.stripTrailingZeros().scale() <= 0) {
                    qty = first.intValue();
                    price = decimals.get(1);
                }
            }

            if (qty == null || qty <= 0 || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("CN BAD QTY/PRICE line {}: sym={}, qty={}, price={}, ints={}, decs={}, after=[{}]",
                    lineNum, symbol, qty, price, integers, decimals, truncateLog(afterSide));
                continue;
            }
            if (price.compareTo(new BigDecimal("0.01")) < 0) {
                log.info("CN PRICE TOO LOW line {}: sym={}, price={}", lineNum, symbol, price);
                continue;
            }

            log.info("CN TRADE FOUND — {} {} x {} @ {} on {} (line {})",
                isSell ? "SELL" : "BUY", symbol, qty, price, date, lineNum);

            results.add(ParsedEmail.builder()
                .type(isSell ? ParsedEmail.Type.TRADE_SELL : ParsedEmail.Type.TRADE_BUY)
                .symbol(symbol)
                .exchange(exchange)
                .quantity(qty)
                .price(price)
                .tradeDate(date != null ? date : LocalDate.now())
                .sourceDescription(String.format("%s CN %s: %s x %d @ ₹%.2f",
                    brokerLabel, isSell ? "Sell" : "Buy", symbol, qty, price))
                .build());
        }

        log.info("CN PARSE DONE — broker={}, totalLines={}, tradesFound={}", brokerLabel, lineNum, results.size());

        // Second pass: if no trades found with B/S-per-line, try multi-line block parsing
        // mStock and some brokers lay out each trade across multiple lines, where the Buy/Sell
        // indicator is on a DIFFERENT line from the symbol/qty/price
        if (results.isEmpty()) {
            log.info("CN PASS 2 — trying multi-line block parsing");
            results = parseMultiLineBlocks(text, date, exchange, brokerLabel);
        }

        return results;
    }

    private static List<ParsedEmail> parseMultiLineBlocks(String text, LocalDate date, String exchange, String brokerLabel) {
        List<ParsedEmail> results = new ArrayList<>();
        String[] lines = text.split("\\n");

        // Collect all lines that look like they contain trade data (numbers + possible symbols)
        // The strategy: find lines with a known stock symbol + numbers, infer B/S from context
        boolean defaultBuy = !text.toUpperCase().contains("SELL") || text.toUpperCase().contains("BUY");
        boolean hasBuySection = false, hasSellSection = false;
        boolean inBuySection = true;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            String upper = trimmed.toUpperCase();

            // Track section headers: "BUY" / "SELL" sections in some contract notes
            if (upper.matches(".*\\bBUY\\b.*") && !upper.contains("SELL") && trimmed.length() < 40) {
                inBuySection = true;
                hasBuySection = true;
                continue;
            }
            if (upper.matches(".*\\bSELL\\b.*") && !upper.contains("BUY") && trimmed.length() < 40) {
                inBuySection = false;
                hasSellSection = true;
                continue;
            }

            if (trimmed.length() < 5) continue;
            if (looksLikeHeader(trimmed)) continue;

            // Look for lines with: SYMBOL + EQ (equity segment marker) + numbers
            Matcher eqMatcher = Pattern.compile("\\b([A-Z][A-Z0-9&-]{1,19})\\s+EQ\\b").matcher(trimmed);
            if (eqMatcher.find()) {
                String symbol = eqMatcher.group(1);
                if (SKIP_WORDS.contains(symbol)) continue;

                // Detect B/S on this line or nearby lines
                boolean isSell = !inBuySection;
                Matcher sideMatcher = CN_SIDE.matcher(trimmed);
                if (sideMatcher.find()) {
                    String s = sideMatcher.group(1).toUpperCase();
                    isSell = "S".equals(s) || "SELL".equals(s);
                }

                // Extract numbers from this line and following lines (trade data might span lines)
                String numberSource = trimmed;
                for (int j = i + 1; j < Math.min(i + 3, lines.length); j++) {
                    String next = lines[j].trim();
                    if (next.length() < 3) continue;
                    if (next.matches(".*\\d+.*")) numberSource += " " + next;
                    else break;
                }

                List<BigDecimal> decimals = new ArrayList<>();
                Matcher decM = CN_DECIMAL.matcher(numberSource);
                while (decM.find()) {
                    BigDecimal val = ParserUtil.parseMoney(decM.group(1));
                    if (val != null) decimals.add(val);
                }

                List<Integer> integers = new ArrayList<>();
                Matcher intM = CN_INTEGER.matcher(numberSource);
                while (intM.find()) {
                    String raw = intM.group(1);
                    if (!raw.contains(".")) {
                        Integer val = ParserUtil.parseQty(raw);
                        if (val != null && val > 0 && val < 100000) integers.add(val);
                    }
                }

                Integer qty = null;
                BigDecimal price = null;
                if (!integers.isEmpty() && !decimals.isEmpty()) {
                    qty = integers.get(0);
                    price = decimals.get(0);
                    if (price.compareTo(new BigDecimal("100000")) > 0 && decimals.size() > 1) {
                        price = decimals.get(1);
                    }
                } else if (decimals.size() >= 2) {
                    BigDecimal first = decimals.get(0);
                    if (first.stripTrailingZeros().scale() <= 0 && first.intValue() < 100000) {
                        qty = first.intValue();
                        price = decimals.get(1);
                    }
                }

                if (qty != null && qty > 0 && price != null && price.compareTo(BigDecimal.ZERO) > 0
                        && price.compareTo(new BigDecimal("0.01")) > 0) {
                    log.info("CN PASS2 TRADE — {} {} x {} @ {} (line {}): [{}]",
                        isSell ? "SELL" : "BUY", symbol, qty, price, i + 1, truncateLog(trimmed));
                    results.add(ParsedEmail.builder()
                        .type(isSell ? ParsedEmail.Type.TRADE_SELL : ParsedEmail.Type.TRADE_BUY)
                        .symbol(symbol)
                        .exchange(exchange)
                        .quantity(qty)
                        .price(price)
                        .tradeDate(date != null ? date : LocalDate.now())
                        .sourceDescription(String.format("%s CN %s: %s x %d @ ₹%.2f",
                            brokerLabel, isSell ? "Sell" : "Buy", symbol, qty, price))
                        .build());
                } else {
                    log.info("CN PASS2 SKIP — sym={}, qty={}, price={}, ints={}, decs={}, line {}: [{}]",
                        symbol, qty, price, integers, decimals, i + 1, truncateLog(trimmed));
                }
            }
        }

        if (results.isEmpty()) {
            log.info("CN PASS 2 — also found 0 trades");
        } else {
            log.info("CN PASS 2 — found {} trade(s)", results.size());
        }
        return results;
    }

    private static String truncateLog(String s) {
        return s != null && s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    public static LocalDate extractDate(String text, String subject) {
        String dateStr = ParserUtil.findFirst(text, CN_DATE);
        LocalDate date = ParserUtil.parseDate(dateStr);
        if (date != null) return date;
        // Fallback: try subject line for date
        if (subject != null) {
            Matcher m = CN_DATE.matcher(subject);
            if (m.find()) return ParserUtil.parseDate(m.group(1));
        }
        return null;
    }

    private static String findSymbol(String text) {
        Matcher m = CN_SYM.matcher(text);
        while (m.find()) {
            String candidate = m.group(1);
            // Broker client codes look like "MA7468533" (short letter prefix + long digit
            // run) and are printed at the top of every contract note before the actual
            // traded stock name. Without this guard, findSymbol() picks the client code up
            // as if it were the stock, silently misfiling real trades under a fake symbol.
            if (candidate.length() >= 2 && !SKIP_WORDS.contains(candidate)
                    && !com.marketai.common.util.FinancialDataValidator.looksLikeClientCode(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean looksLikeHeader(String line) {
        String lower = line.toLowerCase();
        return (lower.contains("sr.") || lower.contains("sr ")) && lower.contains("symbol") && lower.contains("qty")
            || lower.contains("order no") && lower.contains("trade no")
            || lower.contains("particulars") && lower.contains("debit") && lower.contains("credit")
            || lower.contains("page ") && lower.contains(" of ")
            || lower.startsWith("total") || lower.startsWith("net total")
            || lower.startsWith("grand total") || lower.startsWith("sub total")
            || lower.contains("brokerage") && lower.contains("turnover")
            || lower.contains("stamp duty") || lower.contains("transaction charges")
            || lower.contains("securities transaction tax")
            || lower.contains("clearing charges") || lower.contains("sebi fees");
    }
}
