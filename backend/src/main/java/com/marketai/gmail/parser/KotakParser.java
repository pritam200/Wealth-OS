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
public class KotakParser implements EmailParser {

    private static final Pattern SYMBOL  = Pattern.compile("(?:Script(?:\\s+Name)?|Stock|Symbol|Security)[:\\s]+([A-Z][A-Z0-9&-]{1,20})", Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY     = Pattern.compile("(?:Qty|Quantity|No\\.?\\s+of\\s+Shares?)[:\\s]+(\\d[\\d,]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE   = Pattern.compile("(?:Trade\\s*Price|Price|Rate)[:\\s]+(?:Rs\\.?\\s*|₹\\s*)?([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE    = Pattern.compile("(?:Trade\\s*Date|Order\\s*Date|Date)[:\\s]+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE    = Pattern.compile("(?:Transaction\\s*Type|Order\\s*Type|Buy/Sell)[:\\s]+(Buy|Sell|B|S)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean canParse(String from, String subject) {
        return ParserUtil.containsIgnoreCase(from, "kotak", "kotaksec")
            && ParserUtil.containsIgnoreCase(subject, "trade", "contract", "order", "confirmation");
    }

    @Override
    public List<ParsedEmail> parse(String from, String subject, String bodyText) {
        List<ParsedEmail> results = new ArrayList<>();
        String text = bodyText != null ? bodyText : "";

        String symbol   = ParserUtil.findFirst(text, SYMBOL);
        String qtyStr   = ParserUtil.findFirst(text, QTY);
        String priceStr = ParserUtil.findFirst(text, PRICE);
        String dateStr  = ParserUtil.findFirst(text, DATE);
        String typeStr  = ParserUtil.findFirst(text, TYPE);

        if (symbol == null || qtyStr == null || priceStr == null) return results;

        Integer qty      = ParserUtil.parseQty(qtyStr);
        BigDecimal price = ParserUtil.parseMoney(priceStr);
        LocalDate date   = ParserUtil.parseDate(dateStr);
        if (qty == null || price == null) return results;

        boolean isSell = typeStr != null && (typeStr.equalsIgnoreCase("Sell") || typeStr.equalsIgnoreCase("S"));
        ParsedEmail.Type type = isSell ? ParsedEmail.Type.TRADE_SELL : ParsedEmail.Type.TRADE_BUY;

        results.add(ParsedEmail.builder()
            .type(type)
            .symbol(symbol.toUpperCase())
            .exchange("NSE")
            .quantity(qty)
            .price(price)
            .tradeDate(date != null ? date : LocalDate.now())
            .sourceDescription(String.format("Kotak %s: %s x %d @ ₹%.2f", isSell ? "Sell" : "Buy", symbol, qty, price))
            .build());
        return results;
    }
}
