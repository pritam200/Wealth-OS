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
public class UpstoxParser implements EmailParser {

    private static final Pattern SYMBOL  = Pattern.compile("(?:Symbol|Scrip(?:Name)?|Instrument)[:\\s]+([A-Z][A-Z0-9&-]{1,20})", Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY     = Pattern.compile("(?:Qty|Quantity|Shares?)[:\\s]+(\\d[\\d,]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE   = Pattern.compile("(?:Trade\\s*Price|Price|Rate|Avg\\.?\\s*Price)[:\\s]+(?:Rs\\.?\\s*|₹\\s*)?([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE    = Pattern.compile("(?:Trade\\s*Date|Date)[:\\s]+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUY     = Pattern.compile("\\b(Buy|Bought|Purchase[d]?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELL    = Pattern.compile("\\b(Sell|Sold|Sale|Redemption)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXCHANGE= Pattern.compile("\\b(NSE|BSE)\\b");

    @Override
    public boolean canParse(String from, String subject) {
        boolean fromUpstox = ParserUtil.containsIgnoreCase(from, "upstox");
        boolean tradeSubject = ParserUtil.containsIgnoreCase(subject, "trade", "order", "contract", "confirmation", "executed", "buy", "sell", "bought", "sold",
            "ecn", "statement", "settlement", "note", "bill");
        return fromUpstox && tradeSubject;
    }

    @Override
    public List<ParsedEmail> parse(String from, String subject, String bodyText) {
        List<ParsedEmail> results = new ArrayList<>();
        String text = bodyText != null ? bodyText : "";

        List<String> symbols = ParserUtil.findAll(text, SYMBOL);
        List<String> qtys    = ParserUtil.findAll(text, QTY);
        List<String> prices  = ParserUtil.findAll(text, PRICE);
        String dateStr  = ParserUtil.findFirst(text, DATE);
        String exchange = ParserUtil.findFirst(text, EXCHANGE);

        int count = Math.min(symbols.size(), Math.min(qtys.size(), prices.size()));
        if (count == 0) return ContractNoteHelper.parseContractNoteRows(text, subject, "Upstox");

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
            boolean isSell = SELL.matcher(text).find();
            type = isSell ? ParsedEmail.Type.TRADE_SELL : ParsedEmail.Type.TRADE_BUY;
        }

        for (int i = 0; i < count; i++) {
            String sym = symbols.get(i);
            Integer qty = ParserUtil.parseQty(qtys.get(i));
            BigDecimal price = ParserUtil.parseMoney(prices.get(i));
            if (qty == null || price == null) continue;

            results.add(ParsedEmail.builder()
                .type(type)
                .symbol(sym.toUpperCase().replace(".NS","").replace(".BSE",""))
                .exchange(exchange != null ? exchange : "NSE")
                .quantity(qty)
                .price(price)
                .tradeDate(date != null ? date : LocalDate.now())
                .sourceDescription(String.format("UPStox %s: %s x %d @ ₹%.2f", type == ParsedEmail.Type.TRADE_BUY ? "Buy" : "Sell", sym, qty, price))
                .build());
        }
        return results;
    }
}
