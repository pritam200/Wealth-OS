package com.marketai.ai.intel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmProviderRouter;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.parser.ParserUtil;
import com.marketai.market.entity.Stock;
import com.marketai.market.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Semantic classification of financial emails the deterministic parsers couldn't handle.
 *
 * Deterministic-safety boundary: the model supplies only *semantics* — which kind of
 * transaction this is, and which text spans the figures came from. Every number is re-read
 * through {@link LlmJsonParser} (which refuses to coerce), every ticker is resolved against
 * the real stock master, and nothing is written to a financial table from here — the caller
 * hands an accepted {@link ParsedEmail} to the normal importer, which applies the SHA-256
 * fingerprint gate as usual.
 *
 * Anything below the confidence threshold, or any classification that shouldn't be booked
 * automatically, becomes a review row rather than being silently dropped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailIntelAgent {

    private final LlmProviderRouter llm;
    private final LlmJsonParser json;
    private final AiAuditService audit;
    private final ObjectMapper objectMapper;
    private final MarketDataService marketDataService;

    @Value("${app.llm.min-confidence:0.85}")
    private double minConfidence;

    private static final String TASK = "EMAIL_CLASSIFY";
    private static final int MAX_BODY_CHARS = 6000;

    // Cheap prefilter so newsletters and calendar invites never reach a paid-in-seconds local
    // model call. Trade vocabulary ("bought"/"sold"/"contract note") is included because a
    // broker confirmation often states quantities and a price with no currency symbol at all —
    // keying only on "₹"/"Rs." would discard real trades before they were ever classified.
    private static final String[] MONEY_SIGNALS = {
        "₹", "rs.", "rs ", "inr", "debited", "credited", "spent", "paid", "purchase",
        "sip", "mutual fund", "nav", "folio", "fd", "rd", "salary", "dividend", "upi",
        "transaction", "payment", "emi", "invested", "redeemed", "withdrawn", "balance",
        "transfer", "neft", "imps", "rtgs",
        "bought", "sold", "buy", "sell", "trade", "contract note", "order", "shares",
        "units", "brokerage", "settlement", "demat", "maturity", "interest", "refund",
    };

    private static final String SYSTEM_INSTRUCTION =
        "You classify Indian bank/broker/AMC emails. Return ONLY a JSON object:\n" +
        "{\"classification\": one of UPI_EXPENSE|CARD_EXPENSE|NETBANKING_EXPENSE|ATM_WITHDRAWAL|" +
        "AUTO_DEBIT_EXPENSE|EMI_PAYMENT|BILL_PAYMENT|CARD_BILL_GENERATED|CARD_BILL_PAID|" +
        "STOCK_BUY|STOCK_SELL|DIVIDEND|MF_SIP|MF_LUMPSUM|MF_REDEMPTION|MF_SWITCH|" +
        "FD_OPEN|FD_MATURITY|RD_OPEN|RD_INSTALLMENT|SALARY|INTEREST_CREDIT|REFUND|RENTAL_INCOME|" +
        "INTERNAL_TRANSFER|SELF_TRANSFER|OTP_OR_ALERT|PROMOTIONAL|STATEMENT_ONLY|UNKNOWN,\n" +
        "\"confidence\": number 0..1 (your genuine certainty; be conservative),\n" +
        "\"extractedFields\": {\"amount\": number|null, \"date\": \"yyyy-MM-dd\"|null, " +
        "\"merchant\": string|null, \"symbol\": exact NSE ticker|null, \"quantity\": number|null, " +
        "\"price\": number|null, \"units\": number|null, \"nav\": number|null, \"folio\": string|null, " +
        "\"fundName\": string|null, \"bank\": string|null, \"cardLast4\": string|null, " +
        "\"paymentMethod\": string|null, \"counterparty\": string|null},\n" +
        "\"reasoning\": one sentence on why this classification,\n" +
        "\"evidence\": the exact sentence from the email the amount came from\n}\n" +
        "Rules: never invent a value — use null when the email doesn't state it. " +
        "Use INTERNAL_TRANSFER or SELF_TRANSFER when money moves between the user's own accounts " +
        "(e.g. bank to broker/MF, or between own bank accounts) — this is NOT an expense. " +
        "For a stock trade give the EXACT NSE ticker (ADANIENT not ADANI, LT not LARSEN); if unsure use null. " +
        "Lower your confidence when the email is ambiguous, partially quoted, or a forwarded digest.";

    public boolean looksFinancial(String subject, String body) {
        String text = (subject == null ? "" : subject) + "\n" + (body == null ? "" : body);
        return ParserUtil.containsIgnoreCase(text, MONEY_SIGNALS);
    }

    public boolean isEnabled() { return llm.isEnabled(); }

    public EmailIntelResult classify(Long userId, String from, String subject, String body, String gmailMessageId) {
        String text = (subject == null ? "" : subject) + "\n" + (body == null ? "" : body);
        if (!looksFinancial(subject, body)) {
            return EmailIntelResult.builder()
                .outcome(EmailIntelResult.Outcome.NOT_A_TRANSACTION)
                .type(EmailIntelType.PROMOTIONAL)
                .reasoning("No monetary signal in subject or body")
                .build();
        }

        String truncated = text.length() > MAX_BODY_CHARS ? text.substring(0, MAX_BODY_CHARS) : text;
        String prompt = "From: " + from + "\nSubject: " + subject + "\n\n" + truncated;

        LlmCompletion completion;
        try {
            completion = llm.complete(SYSTEM_INSTRUCTION, prompt);
        } catch (LlmUnavailableException e) {
            audit.recordFailure(userId, TASK, gmailMessageId, SYSTEM_INSTRUCTION, prompt,
                "UNAVAILABLE", "No LLM provider available: " + e.getMessage());
            return unresolved("The classifier was unavailable, so this email was left unprocessed.");
        }

        Optional<JsonNode> parsedJson = json.parse(completion.getText());
        if (!parsedJson.isPresent()) {
            audit.record(userId, TASK, gmailMessageId, SYSTEM_INSTRUCTION, prompt, completion, null,
                "PARSE_FAILED", "Model output was not valid JSON");
            return unresolved("The classifier returned output that could not be read as JSON.");
        }

        JsonNode node = parsedJson.get();
        EmailIntelType type = EmailIntelType.fromLabel(json.str(node, "classification"));
        Double confidence = json.confidence(node, "confidence");
        String reasoning = json.str(node, "reasoning");
        String evidence = json.str(node, "evidence");
        JsonNode fields = node.get("extractedFields");
        String fieldsJson = fields != null ? fields.toString() : null;

        // Non-transactional mail needs no review row — skipping a promo email loses nothing.
        if (type == EmailIntelType.OTP_OR_ALERT || type == EmailIntelType.PROMOTIONAL) {
            audit.record(userId, TASK, gmailMessageId, SYSTEM_INSTRUCTION, prompt, completion, confidence,
                "ACCEPTED", "Classified as non-transactional: " + type);
            return EmailIntelResult.builder()
                .outcome(EmailIntelResult.Outcome.NOT_A_TRANSACTION)
                .type(type).confidence(confidence).reasoning(reasoning).evidence(evidence)
                .extractedFieldsJson(fieldsJson)
                .build();
        }

        // Unknown confidence is treated exactly like low confidence — never as permission.
        if (confidence == null || confidence < minConfidence) {
            String why = confidence == null
                ? "The classifier did not report a usable confidence score."
                : String.format("Confidence %.2f is below the %.2f threshold required to import automatically.", confidence, minConfidence);
            audit.record(userId, TASK, gmailMessageId, SYSTEM_INSTRUCTION, prompt, completion, confidence,
                "REVIEW_REQUIRED", why);
            return review(type, confidence, reasoning, evidence, fieldsJson, why);
        }

        if (!type.isImportable()) {
            String why = type.isTransfer()
                ? "Looks like a movement between your own accounts — importing it as income or spending would distort net worth."
                : "This kind of email (" + type + ") is not booked automatically.";
            audit.record(userId, TASK, gmailMessageId, SYSTEM_INSTRUCTION, prompt, completion, confidence,
                "REVIEW_REQUIRED", why);
            return review(type, confidence, reasoning, evidence, fieldsJson, why);
        }

        // Confident and bookable — rebuild a ParsedEmail using strict readers only.
        ParsedEmail pe = toParsedEmail(type, fields, evidence);
        if (pe == null) {
            String why = "The classifier was confident but the figures it returned were incomplete or unreadable.";
            audit.record(userId, TASK, gmailMessageId, SYSTEM_INSTRUCTION, prompt, completion, confidence,
                "REVIEW_REQUIRED", why);
            return review(type, confidence, reasoning, evidence, fieldsJson, why);
        }

        audit.record(userId, TASK, gmailMessageId, SYSTEM_INSTRUCTION, prompt, completion, confidence,
            "ACCEPTED", "Classified as " + type);
        return EmailIntelResult.builder()
            .outcome(EmailIntelResult.Outcome.IMPORT)
            .type(type).confidence(confidence).reasoning(reasoning).evidence(evidence)
            .extractedFieldsJson(fieldsJson).parsed(pe)
            .build();
    }

    private EmailIntelResult unresolved(String reason) {
        return EmailIntelResult.builder()
            .outcome(EmailIntelResult.Outcome.UNRESOLVED)
            .type(EmailIntelType.UNKNOWN).reviewReason(reason)
            .build();
    }

    private EmailIntelResult review(EmailIntelType type, Double confidence, String reasoning,
                                    String evidence, String fieldsJson, String why) {
        return EmailIntelResult.builder()
            .outcome(EmailIntelResult.Outcome.REVIEW_REQUIRED)
            .type(type).confidence(confidence).reasoning(reasoning).evidence(evidence)
            .extractedFieldsJson(fieldsJson).reviewReason(why)
            .build();
    }

    /**
     * Builds a ParsedEmail from the model's fields using only strict readers. Returns null when
     * a figure the target type genuinely needs is missing — the email then goes to review,
     * because a booked record with a guessed amount is worse than one a human confirms.
     */
    ParsedEmail toParsedEmail(EmailIntelType type, JsonNode fields, String evidence) {
        if (fields == null) return null;

        BigDecimal amount = json.decimal(fields, "amount");
        BigDecimal price = json.decimal(fields, "price");
        BigDecimal units = json.decimal(fields, "units");
        Integer quantity = json.integer(fields, "quantity");
        LocalDate date = parseDate(json.str(fields, "date"));

        ParsedEmail.Type target = type.importAs();

        if (target == ParsedEmail.Type.TRADE_BUY || target == ParsedEmail.Type.TRADE_SELL) {
            String symbol = resolveSymbol(json.str(fields, "symbol"));
            if (symbol == null || price == null || quantity == null) return null;
            return base(target, date, evidence)
                .symbol(symbol).price(price).quantity(quantity).amount(amount)
                .build();
        }

        if (target == ParsedEmail.Type.MF_SIP || target == ParsedEmail.Type.MF_REDEEM) {
            if (amount == null) return null;
            return base(target, date, evidence)
                .amount(amount).units(units).nav(json.decimal(fields, "nav"))
                .folio(json.str(fields, "folio")).fundName(json.str(fields, "fundName"))
                .build();
        }

        if (target == ParsedEmail.Type.FD_OPEN) {
            BigDecimal principal = amount != null ? amount : json.decimal(fields, "principal");
            if (principal == null) return null;
            return base(target, date, evidence)
                .principal(principal).bank(json.str(fields, "bank"))
                .rate(json.decimal(fields, "rate")).startDate(date)
                .build();
        }

        if (target == ParsedEmail.Type.RD_OPEN) {
            if (amount == null) return null;
            return base(target, date, evidence)
                .monthlyAmount(amount).bank(json.str(fields, "bank"))
                .rate(json.decimal(fields, "rate")).startDate(date)
                .build();
        }

        // EXPENSE / INCOME / DIVIDEND / CARD_BILL all need an amount and nothing more.
        if (amount == null) return null;
        return base(target, date, evidence)
            .amount(amount)
            .merchant(json.str(fields, "merchant"))
            .paymentMethod(firstNonBlank(json.str(fields, "paymentMethod"), json.str(fields, "bank")))
            .cardLast4(json.str(fields, "cardLast4"))
            .incomeSource(type == EmailIntelType.SALARY ? "Salary"
                        : type == EmailIntelType.INTEREST_CREDIT ? "Interest"
                        : type == EmailIntelType.RENTAL_INCOME ? "Rental" : null)
            .build();
    }

    private ParsedEmail.ParsedEmailBuilder base(ParsedEmail.Type type, LocalDate date, String evidence) {
        return ParsedEmail.builder()
            .type(type)
            .tradeDate(date != null ? date : LocalDate.now())
            .sourceDescription("AI-classified: " + (evidence != null && evidence.length() > 180
                ? evidence.substring(0, 180) : evidence));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }

    private LocalDate parseDate(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;   // unparseable date falls back to today via base(), never guessed
        }
    }

    /**
     * Same guarantee as the older extractor: an AI-proposed ticker is confirmed against the
     * real stock master, and an ambiguous one is refused rather than booked. A plausible-looking
     * but wrong ticker would create a second holding for a stock already owned.
     */
    private String resolveSymbol(String candidate) {
        if (candidate == null) return null;
        String upper = candidate.trim().toUpperCase().replaceAll("[^A-Z0-9&-]", "");
        if (upper.isEmpty()) return null;
        List<Stock> hits;
        try {
            hits = marketDataService.searchStocks(upper);
        } catch (Exception e) {
            log.debug("Symbol resolution failed for '{}': {}", candidate, e.getMessage());
            return null;
        }
        for (Stock s : hits) {
            if (upper.equalsIgnoreCase(s.getSymbol())) return s.getSymbol();
        }
        return hits.size() == 1 ? hits.get(0).getSymbol() : null;
    }
}
