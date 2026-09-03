package com.marketai.gmail.parser;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
@Order(1)
public class MstockParser implements EmailParser {

    private static final Pattern SYMBOL  = Pattern.compile("(?:Symbol|Scrip|Stock|Instrument)[:\\s]+([A-Z][A-Z0-9&-]{1,20})", Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY     = Pattern.compile("(?:Qty|Quantity)[:\\s]+(\\d[\\d,]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE   = Pattern.compile("(?:Price|Rate|Avg\\.?\\s*Price)[:\\s]+(?:Rs\\.?\\s*|₹\\s*)?([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE    = Pattern.compile("(?:Trade\\s*Date|Date)[:\\s]+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean canParse(String from, String subject) {
        return ParserUtil.containsIgnoreCase(from, "mstock", "miraeasset", "mirae")
            && ParserUtil.containsIgnoreCase(subject, "trade", "contract", "order", "confirmation", "executed",
                "ecn", "statement", "settlement", "note", "bill");
    }

    @Override
    public List<ParsedEmail> parse(String from, String subject, String bodyText) {
        String text = bodyText != null ? bodyText : "";

        // Try structured "Label: Value" format first (email body)
        List<ParsedEmail> results = parseStructured(text, subject);
        if (!results.isEmpty()) return results;

        // Fallback: contract note PDF tabular format
        return ContractNoteHelper.parseContractNoteRows(text, subject, "MStock");
    }

    private List<ParsedEmail> parseStructured(String text, String subject) {
        List<ParsedEmail> results = new ArrayList<>();

        List<String> symbols = ParserUtil.findAll(text, SYMBOL);
        List<String> qtys    = ParserUtil.findAll(text, QTY);
        List<String> prices  = ParserUtil.findAll(text, PRICE);
        String dateStr  = ParserUtil.findFirst(text, DATE);

        int count = Math.min(symbols.size(), Math.min(qtys.size(), prices.size()));
        if (count == 0) return results;

        LocalDate date = ParserUtil.parseDate(dateStr);

        String subjectLower = (subject != null ? subject : "").toLowerCase();
        boolean subjectSell = subjectLower.contains("sell") || subjectLower.contains("sold") || subjectLower.contains("sale");
        boolean subjectBuy = subjectLower.contains("buy") || subjectLower.contains("bought") || subjectLower.contains("purchase");
        ParsedEmail.Type type;
        if (subjectSell && !subjectBuy) {
            type = ParsedEmail.Type.TRADE_SELL;
        } else if (subjectBuy && !subjectSell) {
            type = ParsedEmail.Type.TRADE_BUY;
        } else {
            boolean isSell = ParserUtil.containsIgnoreCase(text, "sell", "sold", "sale", "redemption");
            type = isSell ? ParsedEmail.Type.TRADE_SELL : ParsedEmail.Type.TRADE_BUY;
        }

        for (int i = 0; i < count; i++) {
            String sym = symbols.get(i);
            Integer qty = ParserUtil.parseQty(qtys.get(i));
            BigDecimal price = ParserUtil.parseMoney(prices.get(i));
            if (qty == null || price == null) continue;

            results.add(ParsedEmail.builder()
                .type(type)
                .symbol(sym.toUpperCase())
                .exchange("NSE")
                .quantity(qty)
                .price(price)
                .tradeDate(date != null ? date : LocalDate.now())
                .sourceDescription(String.format("MStock %s: %s x %d @ ₹%.2f", type == ParsedEmail.Type.TRADE_SELL ? "Sell" : "Buy", sym, qty, price))
                .build());
        }
        return results;
    }
}
