package com.marketai.gmail.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.client.GeminiClient;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.parser.ParserUtil;
import com.marketai.market.entity.Stock;
import com.marketai.market.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Fallback for any financial email the deterministic regex parsers (BankTransactionParser,
 * broker/RTA parsers, etc.) fail to extract anything from — arbitrary phrasing, a bank's
 * unusual wording, a debit alert that doesn't match the mandatory "Rs./₹/INR"-prefixed
 * amount regex, and so on. Only invoked when the regular parser chain comes back empty
 * (see GmailSyncService), so it never overrides an already-working deterministic parser.
 *
 * Reuses the existing GeminiClient (same one AnalystService uses for narrative text) —
 * no new AI provider, no new config beyond the existing app.gemini.* keys.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiEmailExtractor {

    private final GeminiClient gemini;
    private final ObjectMapper objectMapper;
    private final MarketDataService marketDataService;

    // Cheap prefilter so newsletters/calendar invites/social notifications never reach the
    // AI call at all — keeps Gemini usage limited to emails that are actually money-shaped.
    private static final String[] MONEY_SIGNALS = {
        "₹", "rs.", "rs ", "inr", "debited", "credited", "spent", "paid", "purchase",
        "sip", "mutual fund", "nav", "folio", "fd", "rd", "salary", "dividend", "upi",
        "transaction", "payment", "emi", "invested", "redeemed", "withdrawn", "balance"
    };

    private static final String SYSTEM_INSTRUCTION =
        "You extract financial transactions from Indian bank/broker/investment emails. " +
        "Return ONLY a JSON array, no prose, no markdown fences. Each element has exactly these fields " +
        "(omit a field if unknown, use null, never invent values):\n" +
        "{\"type\": one of TRADE_BUY|TRADE_SELL|FD_OPEN|RD_OPEN|MF_SIP|DIVIDEND|INCOME|EXPENSE|CARD_BILL, " +
        "\"symbol\": for a trade/dividend, the EXACT NSE trading ticker (e.g. RELIANCE, TCS, HDFCBANK, LT, ADANIENT) — " +
        "never a shortened or partial company name (e.g. write ADANIENT not ADANI, AXISBANK not AXIS, HEROMOTOCO not HERO, " +
        "LT not LARSEN). If you are not certain of the exact ticker, use null — a wrong guess is worse than no answer. " +
        "\"quantity\": integer units/shares, \"price\": per-unit price, " +
        "\"amount\": total transaction amount, " +
        "\"merchant\": the actual payee/merchant/company name — for expenses this is who was paid (e.g. Swiggy, Amazon, Airtel), " +
        "for dividends this is the company name (e.g. TCS, Infosys, Reliance Industries Ltd), " +
        "for income this is who paid (employer name, bank name, etc.), " +
        "\"paymentMethod\": payment channel (e.g. HDFC Bank, Google Pay UPI, Paytm, Credit Card), " +
        "\"category\": one of Food|Shopping|Travel|Fuel|Bills|Medical|Entertainment|EMI|UPI|Uncategorized if an expense, " +
        "\"incomeSource\": Salary|Interest|Dividend|Other if income, \"bank\": bank/institution name, " +
        "\"fundName\": mutual fund scheme name if MF_SIP, \"nav\": per-unit NAV if MF, \"units\": MF units if known, " +
        "\"tradeDate\": ISO date yyyy-MM-dd if mentioned, else null}\n" +
        "If the email is not a real financial transaction (newsletter, OTP, marketing, a document you cannot read " +
        "the numbers of), return an empty array []. Never guess a transaction that isn't clearly stated in the text.";

    /** Shared with GmailSyncService's PDF-attachment gate, so both the AI text fallback and
     *  the "queue this locked PDF for the user" decision use the same definition of
     *  "looks like a financial email" instead of two independently-drifting keyword lists. */
    public boolean isFinanciallyRelevant(String subject, String bodyText) {
        String text = (subject == null ? "" : subject) + "\n" + (bodyText == null ? "" : bodyText);
        return ParserUtil.containsIgnoreCase(text, MONEY_SIGNALS);
    }

    public List<ParsedEmail> extract(String from, String subject, String bodyText) {
        String text = (subject == null ? "" : subject) + "\n" + (bodyText == null ? "" : bodyText);
        if (!ParserUtil.containsIgnoreCase(text, MONEY_SIGNALS)) return new ArrayList<>();

        // Gemini has a real per-request cost/latency and a token limit — cap the input.
        String truncated = text.length() > 6000 ? text.substring(0, 6000) : text;

        String raw;
        try {
            raw = gemini.generateContent(SYSTEM_INSTRUCTION, "From: " + from + "\nSubject: " + subject + "\n\n" + truncated);
        } catch (Exception e) {
            log.debug("AI email extraction call failed: {}", e.getMessage());
            return new ArrayList<>();
        }
        if (raw == null) return new ArrayList<>();

        String json = raw.trim();
        // Strip ```json ... ``` fences if Gemini added them despite instructions.
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```[a-zA-Z]*\\n?", "").replaceFirst("```\\s*$", "").trim();
        }
        if (!json.startsWith("[")) return new ArrayList<>(); // not the JSON array we asked for — bail safely

        List<ParsedEmail> out = new ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) return out;
            for (JsonNode node : arr) {
                ParsedEmail pe = toParsedEmail(node);
                if (pe != null) out.add(pe);
            }
        } catch (Exception e) {
            log.debug("Could not parse AI extraction JSON: {} — raw: {}", e.getMessage(),
                json.length() > 200 ? json.substring(0, 200) : json);
        }
        return out;
    }

    private ParsedEmail toParsedEmail(JsonNode node) {
        String typeStr = text(node, "type");
        if (typeStr == null) return null;
        ParsedEmail.Type type;
        try {
            type = ParsedEmail.Type.valueOf(typeStr.trim().toUpperCase());
        } catch (Exception e) {
            return null; // AI returned something outside our enum — skip rather than guess
        }

        BigDecimal amount = number(node, "amount");
        BigDecimal price = number(node, "price");
        LocalDate tradeDate = date(node, "tradeDate");

        // Every branch needs a minimum signal to be worth booking — mirrors the "if amount
        // null return empty" guards the deterministic parsers already use.
        if ((type == ParsedEmail.Type.EXPENSE || type == ParsedEmail.Type.INCOME
                || type == ParsedEmail.Type.DIVIDEND || type == ParsedEmail.Type.CARD_BILL) && amount == null) return null;

        String rawSymbol = text(node, "symbol");
        if ((type == ParsedEmail.Type.TRADE_BUY || type == ParsedEmail.Type.TRADE_SELL)
                && (rawSymbol == null || price == null)) return null;

        // The prompt asks for an exact ticker, but an LLM cannot be trusted to always comply —
        // in practice it regularly returns just the first word of the company name (ADANI for
        // ADANIENT, AXIS for AXISBANK, HERO for HEROMOTOCO, LARSEN for LT, MRS for BECTORFOOD).
        // Each of those is a syntactically valid-looking symbol, so nothing downstream would
        // catch it — it would create a SECOND holding for a stock the user already owns under
        // its real ticker, silently inflating their portfolio value. Resolve against the known
        // stock master (local seed list + Yahoo search fallback, see MarketDataService) before
        // ever booking a trade; reject rather than guess when the resolution is ambiguous.
        if (type == ParsedEmail.Type.TRADE_BUY || type == ParsedEmail.Type.TRADE_SELL) {
            String resolved = resolveSymbol(rawSymbol);
            if (resolved == null) {
                log.warn("AI extractor: could not confidently resolve symbol '{}' to a real NSE/BSE ticker — dropping this trade rather than booking a possibly-wrong one.", rawSymbol);
                return null;
            }
            rawSymbol = resolved;
        }

        Integer quantity = null;
        JsonNode qtyNode = node.get("quantity");
        if (qtyNode != null && !qtyNode.isNull()) {
            try { quantity = Integer.parseInt(qtyNode.asText()); }
            catch (NumberFormatException ignored) {}
        }

        String merchant = text(node, "merchant");
        String paymentMethod = text(node, "paymentMethod");
        if (paymentMethod == null) paymentMethod = text(node, "bank");

        return ParsedEmail.builder()
            .type(type)
            .symbol(rawSymbol)
            .quantity(quantity)
            .price(price)
            .amount(amount)
            .merchant(merchant)
            .paymentMethod(paymentMethod)
            .category(text(node, "category"))
            .incomeSource(text(node, "incomeSource"))
            .bank(text(node, "bank"))
            .fundName(text(node, "fundName"))
            .nav(number(node, "nav"))
            .units(number(node, "units"))
            .tradeDate(tradeDate != null ? tradeDate : LocalDate.now())
            .sourceDescription(describeForHistory(type, amount, rawSymbol, merchant))
            .build();
    }

    /**
     * Resolves an AI-provided symbol/company-name fragment to a real NSE/BSE ticker.
     * Returns the confirmed ticker, or null when resolution isn't confident enough to act on
     * — an unresolved trade is dropped (logged), never booked under a guessed symbol.
     */
    private String resolveSymbol(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return null;
        String upper = candidate.trim().toUpperCase().replaceAll("[^A-Z0-9&-]", "");
        if (upper.isEmpty()) return null;

        List<Stock> hits;
        try {
            hits = marketDataService.searchStocks(upper);
        } catch (Exception e) {
            log.debug("Stock search failed while resolving AI symbol '{}': {}", candidate, e.getMessage());
            return null;
        }

        // Exact symbol match (case-insensitive) is unambiguous even if the search returned
        // other partial hits alongside it — e.g. searching "LT" also surfaces unrelated
        // symbols containing "LT", but LT itself is still the confirmed answer.
        for (Stock s : hits) {
            if (upper.equalsIgnoreCase(s.getSymbol())) return s.getSymbol();
        }
        // No exact hit: only accept a SINGLE candidate result as a confident correction
        // (e.g. "ADANI" -> one hit "ADANIENT"). Multiple or zero results means we cannot
        // tell which real stock was meant, so refuse rather than pick one.
        if (hits.size() == 1) return hits.get(0).getSymbol();
        return null;
    }

    private String describeForHistory(ParsedEmail.Type type, BigDecimal amount, String symbol, String merchant) {
        String amt = amount != null ? String.format("₹%.0f", amount) : "";
        switch (type) {
            case TRADE_BUY: case TRADE_SELL: return "AI-extracted " + type.name() + ": " + symbol + " " + amt;
            case EXPENSE: return "AI-extracted spend: " + amt + (merchant != null ? " at " + merchant : "");
            default: return "AI-extracted " + type.name() + ": " + amt;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText(null);
    }

    private BigDecimal number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        try { return new BigDecimal(v.asText()); } catch (Exception e) { return null; }
    }

    private LocalDate date(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null) return null;
        try { return LocalDate.parse(s.trim(), DateTimeFormatter.ISO_LOCAL_DATE); } catch (Exception e) { return null; }
    }
}
