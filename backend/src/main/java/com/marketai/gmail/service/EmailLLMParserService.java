package com.marketai.gmail.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.intel.EmailIntelResult;
import com.marketai.ai.intel.EmailIntelType;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmProviderRouter;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.ai.review.service.EmailReviewService;
import com.marketai.auth.entity.User;
import com.marketai.document.classify.ClassificationCandidate;
import com.marketai.document.classify.IssuerDomainRegistry;
import com.marketai.document.classify.SenderTrustEvaluator;
import com.marketai.document.extract.ExtractedField;
import com.marketai.document.extract.SpanVerifier;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.parser.SpendCategorizer;
import com.marketai.identity.service.PasswordStrategy;
import com.marketai.market.entity.Stock;
import com.marketai.market.service.MarketDataService;
import com.marketai.mf.entity.CasBalanceSnapshot;
import com.marketai.mf.repository.CasBalanceSnapshotRepository;
import com.marketai.mf.service.MfSchemeLinkService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Single LLM-based replacement for the whole regex parser cascade (18 {@code EmailParser}
 * implementations), the email-body AI fallback ({@code EmailIntelAgent}) and the PDF-text AI
 * fallback ({@code AiEmailExtractor}). Every synced email — with or without a decrypted PDF
 * attachment's text — goes through exactly two LLM calls handled here:
 *
 * <ol>
 *   <li>{@link #classify} — subject/body only. Answers "is this a financial statement, who does
 *       it claim to be from, and what password format (if any) does it imply". Used both to
 *       gate extraction and, by {@link PdfImportService}, to drive the deterministic password
 *       derivation flow (the model only ever names a {@link PasswordStrategy}; it never sees or
 *       produces PAN, DOB, or a password).</li>
 *   <li>{@link #process} — runs extraction on the given source text (email body, or decrypted
 *       PDF text) and does everything downstream: sender-trust gating, span verification,
 *       instrument resolution, confidence gating, persistence via {@link ParsedEmailImporter},
 *       and routing anything uncertain to {@link EmailReviewService}.</li>
 * </ol>
 *
 * <p><b>Deterministic-safety boundary, unchanged from the classes this replaces:</b> the model
 * supplies semantics only. Every number is re-read through {@link LlmJsonParser} (which refuses
 * to coerce), every numeric/date field must cite a verbatim span of the source text
 * ({@link SpanVerifier}), every mutual-fund scheme name is resolved against AMFI
 * ({@link MfSchemeLinkService}) and every equity symbol against the real stock master
 * ({@link MarketDataService}), and nothing below {@code app.llm.min-confidence} is auto-booked —
 * it goes to the review queue instead. Nothing here ever fabricates, estimates, silently drops,
 * or overwrites a financial record.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailLLMParserService {

    private final LlmProviderRouter llm;
    private final LlmJsonParser json;
    private final AiAuditService audit;
    private final MarketDataService marketDataService;
    private final MfSchemeLinkService mfSchemeLinkService;
    private final SenderTrustEvaluator senderTrustEvaluator;
    private final EmailReviewService emailReviewService;
    private final ParsedEmailImporter importer;
    private final CasBalanceSnapshotRepository casBalanceSnapshotRepository;

    @Value("${app.llm.min-confidence:0.85}")
    private double minConfidence;

    private static final int MAX_CHARS = 8000;
    private static final String CLASSIFY_TASK = "EMAIL_LLM_CLASSIFY";
    private static final String EXTRACT_TASK = "EMAIL_LLM_EXTRACT";

    private static final String[] MONEY_SIGNALS = {
        "₹", "rs.", "rs ", "inr", "debited", "credited", "spent", "paid", "purchase",
        "sip", "mutual fund", "nav", "folio", "fd", "rd", "salary", "dividend", "upi",
        "transaction", "payment", "emi", "invested", "redeemed", "withdrawn", "balance",
        "transfer", "neft", "imps", "rtgs", "bought", "sold", "buy", "sell", "trade",
        "contract note", "order", "shares", "units", "brokerage", "settlement", "demat",
        "maturity", "interest", "refund", "statement", "password",
    };

    private static final String CLASSIFY_SYSTEM =
        "You classify an Indian bank/broker/AMC/RTA email. Return ONLY a JSON object, no prose, " +
        "no markdown fences:\n" +
        "{\"is_financial_statement\": true|false, " +
        "\"statement_provider\": one of CAMS|KFINTECH|CDSL|NSDL|ZERODHA|GROWW|UPSTOX|ANGELONE|" +
        "ICICIDIRECT|MSTOCK|HDFC_BANK|ICICI_BANK|AXIS_BANK|SBI|KOTAK|IDFC_FIRST|YES_BANK|" +
        "HDFC_AMC|OTHER|null, " +
        "\"password_hint_type\": one of PAN|PAN_LOWERCASE|DOB|DOB_SHORT|PAN_DOB|PAN_FIRST4_DOB|" +
        "PAN_FIRST5_DOB|USER_DEFINED|null (null when the email states no password format at all; " +
        "USER_DEFINED when it says the password was chosen by the user at request time)}\n" +
        "Never invent a provider or hint that isn't actually stated or clearly implied by the " +
        "sender/content. Use null rather than a guess.";

    private static final String EXTRACT_SYSTEM =
        "You extract financial transactions from an Indian bank/broker/AMC/RTA email or " +
        "statement. Return ONLY a JSON object of the form {\"transactions\": [...], " +
        "\"closing_balances\": [...], \"confidence\": number 0..1 (your genuine certainty " +
        "across all transactions; be conservative)}, no prose, no markdown fences. Each " +
        "transactions[] element has exactly these fields (use null when unknown, never invent " +
        "a value):\n" +
        "{\"instrument_type\": one of MF|EQUITY|BANK|UPI, " +
        "\"transaction_type\": for MF one of PURCHASE|SIP|REDEMPTION|DIVIDEND_REINVEST; " +
        "for EQUITY one of BUY|SELL|DIVIDEND; for BANK/UPI one of CREDIT|DEBIT, " +
        "\"scheme_name\": full MF scheme name exactly as written (e.g. \\\"HDFC Small Cap Fund " +
        "- Direct Plan - Growth\\\"), null unless MF, " +
        "\"plan_type\": Direct|Regular, null unless MF, " +
        "\"option_type\": Growth|IDCW, null unless MF, " +
        "\"folio_number\": string, null unless MF, " +
        "\"symbol\": the EXACT NSE trading ticker (e.g. RELIANCE, TCS, ADANIENT — never a " +
        "shortened form like ADANI or LARSEN; use null if not certain), null unless EQUITY, " +
        "\"isin\": the ISIN code (e.g. INF179K01158, INE002A01018), null unless MF or EQUITY " +
        "and the text clearly states it, " +
        "\"dp_id\": demat Depository Participant id, null unless EQUITY and clearly stated, " +
        "\"client_id\": broker client id, null unless EQUITY and clearly stated, " +
        "\"transaction_date\": ISO date yyyy-MM-dd, " +
        "\"amount_inr\": total transaction amount, " +
        "\"nav\": per-unit NAV, null unless MF, " +
        "\"units\": units transacted, null unless MF, " +
        "\"quantity\": integer shares, null unless EQUITY, " +
        "\"price\": per-share price, null unless EQUITY, " +
        "\"merchant\": payee/merchant/company name, null unless BANK/UPI or DIVIDEND, " +
        "\"payment_method\": payment channel (bank name, UPI app, card), " +
        "\"category\": one of Food|Food Delivery|Groceries|Restaurant / Outing|Shopping|Travel|" +
        "Fuel|Bills|Medical|Entertainment|EMI|UPI|Account Transfer|Uncategorized, null unless " +
        "BANK/UPI debit — a hint only, the app recomputes the real category deterministically, " +
        "\"evidence\": the exact sentence or line from the source text that the amount and date " +
        "were read from — required for every transaction, verbatim, not paraphrased}\n" +
        "A credit card STATEMENT (as opposed to a payment confirmation) lists many individual " +
        "purchases in a table: extract EVERY purchase line item as its own BANK/UPI DEBIT " +
        "transaction, each with its own merchant, amount, date, and evidence line — do not " +
        "collapse them into one transaction and do not extract the statement's aggregate " +
        "\\\"Total Amount Due\\\" / \\\"Minimum Amount Due\\\" figure as a transaction at all; " +
        "it is a bill total, not something that was actually debited yet. A CRED payment or a " +
        "bank debit described as paying/settling a credit card bill is a single transaction " +
        "(the payment itself, not its line items) with transaction_type DEBIT and category " +
        "\\\"Account Transfer\\\".\n" +
        "A CAS/AMC statement often also states a per-folio closing balance separately from any " +
        "transaction rows (e.g. \\\"Closing Balance: 1234.567 units as of 31-Jan-2026\\\"). " +
        "Extract each such line as its own element of closing_balances[], each with exactly " +
        "these fields: {\"folio\": string, \"scheme_name\": full MF scheme name exactly as " +
        "written, \"as_of_date\": ISO date yyyy-MM-dd, \"units\": the stated closing unit " +
        "balance, \"evidence\": the exact sentence or line the folio and units were read from, " +
        "verbatim}. Omit closing_balances entirely (empty array) when the statement states no " +
        "such summary line — never derive or estimate a closing balance from the transaction " +
        "rows yourself.\n" +
        "If nothing in the text is a real, clearly-stated transaction, return {\"transactions\": " +
        "[], \"closing_balances\": [], \"confidence\": 1}. Never guess a transaction, amount, " +
        "date, scheme, ticker, or closing balance that isn't clearly stated in the text.";

    // --- Call 1: classify + password hint -------------------------------------------------

    public record Classification(boolean financialStatement, String statementProvider,
                                 PasswordStrategy passwordHintType) {
        static Classification none() { return new Classification(false, null, null); }
    }

    public boolean looksFinancial(String subject, String body) {
        String text = (subject == null ? "" : subject) + "\n" + (body == null ? "" : body);
        String lower = text.toLowerCase(Locale.ROOT);
        for (String signal : MONEY_SIGNALS) if (lower.contains(signal)) return true;
        return false;
    }

    /**
     * Classifies an email's subject/body only. Used both to gate extraction (via {@link #process})
     * and, by {@link PdfImportService}, purely for its {@code password_hint_type} — the only
     * thing the model ever contributes to password resolution.
     */
    public Classification classify(String from, String subject, String body) {
        if (!looksFinancial(subject, body)) return Classification.none();

        String text = (subject == null ? "" : subject) + "\n" + (body == null ? "" : body);
        String truncated = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        String prompt = "From: " + from + "\nSubject: " + subject + "\n\n" + truncated;

        LlmCompletion completion;
        try {
            completion = llm.complete(CLASSIFY_SYSTEM, prompt);
        } catch (LlmUnavailableException e) {
            audit.recordFailure(null, CLASSIFY_TASK, null, CLASSIFY_SYSTEM, prompt,
                "UNAVAILABLE", "No LLM provider available: " + e.getMessage());
            return Classification.none();
        } catch (Exception e) {
            log.debug("Classification call failed: {}", e.getMessage());
            return Classification.none();
        }

        Optional<JsonNode> parsed = json.parse(completion.getText());
        if (parsed.isEmpty()) {
            audit.record(null, CLASSIFY_TASK, null, CLASSIFY_SYSTEM, prompt, completion, null,
                "PARSE_FAILED", "Model output was not valid JSON");
            return Classification.none();
        }
        JsonNode node = parsed.get();
        boolean isStatement = node.path("is_financial_statement").asBoolean(false);
        String provider = json.str(node, "statement_provider");
        PasswordStrategy strategy = mapPasswordHint(json.str(node, "password_hint_type"));

        audit.record(null, CLASSIFY_TASK, null, CLASSIFY_SYSTEM, prompt, completion, null,
            "ACCEPTED", "is_financial_statement=" + isStatement + " provider=" + provider);
        return new Classification(isStatement, provider, strategy);
    }

    /**
     * Maps the model's free-form hint label onto a concrete {@link PasswordStrategy}. Returns
     * null — fail safe, no password guess, caller must route to review/manual entry — for
     * anything that doesn't map cleanly, including {@code USER_DEFINED} (a password chosen by
     * the user at request time cannot be derived from identity at all).
     */
    PasswordStrategy mapPasswordHint(String raw) {
        if (raw == null) return null;
        String h = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return switch (h) {
            case "PAN", "PAN_UPPERCASE" -> PasswordStrategy.PAN_UPPERCASE;
            case "PAN_LOWERCASE" -> PasswordStrategy.PAN_LOWERCASE;
            case "DOB", "DOB_DDMMYYYY" -> PasswordStrategy.DOB_DDMMYYYY;
            case "DOB_SHORT", "DOB_DDMMYY" -> PasswordStrategy.DOB_DDMMYY;
            case "PAN_DOB", "PAN_PLUS_DOB", "PAN_UPPERCASE_PLUS_DOB_DDMMYYYY" ->
                PasswordStrategy.PAN_UPPERCASE_PLUS_DOB_DDMMYYYY;
            case "PAN_FIRST4_DOB", "PAN_FIRST4_PLUS_DOB_DDMMYYYY" ->
                PasswordStrategy.PAN_FIRST4_PLUS_DOB_DDMMYYYY;
            case "PAN_FIRST5_DOB", "PAN_FIRST5_PLUS_DOB_DDMMYYYY" ->
                PasswordStrategy.PAN_FIRST5_PLUS_DOB_DDMMYYYY;
            default -> null; // USER_DEFINED, UNKNOWN, or anything unrecognised
        };
    }

    // --- Call 2: extraction + everything downstream -----------------------------------------

    public enum Outcome { IMPORTED, REVIEW, NOT_FINANCIAL, UNAVAILABLE }

    @Data
    @Builder
    public static class Result {
        private Outcome outcome;
        @Builder.Default private int imported = 0;
        @Builder.Default private int queuedForReview = 0;
        @Builder.Default private int rejected = 0;
        @Builder.Default private List<String> summaries = new ArrayList<>();
        private String detail;
        private String matchedProvider;
    }

    /**
     * Runs extraction on {@code sourceText} (an email body, or decrypted-and-stripped PDF text)
     * and does everything downstream — sender-trust gating, span verification, instrument
     * resolution, confidence gating, persistence, and review-queue routing. Never throws for a
     * model failure; every outcome lands as either an import or a review row.
     *
     * @param classification the result of a prior {@link #classify} call on the same email
     *                        (subject/body). Optional — pass null when unavailable, in which
     *                        case only the header-based sender-trust check runs (still the same
     *                        defence {@link SenderTrustEvaluator} always performs). Passing it
     *                        adds a second, LLM-claim-aware check: a {@code statement_provider}
     *                        the sending domain doesn't support is treated as spoofed too.
     */
    public Result process(Long userId, User user, String from, String subject, String sourceText,
                          String gmailMessageId, Classification classification) {
        if (!looksFinancial(subject, sourceText)) {
            return Result.builder().outcome(Outcome.NOT_FINANCIAL)
                .detail("No monetary signal in subject or body").build();
        }

        String truncated = sourceText != null && sourceText.length() > MAX_CHARS
            ? sourceText.substring(0, MAX_CHARS) : sourceText;
        String prompt = "From: " + from + "\nSubject: " + subject + "\n\n" + truncated;

        LlmCompletion completion;
        try {
            completion = llm.complete(EXTRACT_SYSTEM, prompt);
        } catch (LlmUnavailableException e) {
            audit.recordFailure(userId, EXTRACT_TASK, gmailMessageId, EXTRACT_SYSTEM, prompt,
                "UNAVAILABLE", "No LLM provider available: " + e.getMessage());
            enqueueWholeEmailForReview(userId, gmailMessageId, from, subject,
                "The extractor was unavailable, so this email was left unprocessed.");
            return Result.builder().outcome(Outcome.UNAVAILABLE)
                .queuedForReview(1).detail(e.getMessage()).build();
        }

        Optional<JsonNode> parsedJson = json.parse(completion.getText());
        if (parsedJson.isEmpty()) {
            audit.record(userId, EXTRACT_TASK, gmailMessageId, EXTRACT_SYSTEM, prompt, completion, null,
                "PARSE_FAILED", "Model output was not valid JSON");
            enqueueWholeEmailForReview(userId, gmailMessageId, from, subject,
                "The extractor returned output that could not be read as JSON.");
            return Result.builder().outcome(Outcome.REVIEW).queuedForReview(1)
                .detail("Model output was not valid JSON").build();
        }

        JsonNode root = parsedJson.get();
        Double overallConfidence = json.confidence(root, "confidence");

        // --- Sender-trust gate. Runs once per email, ahead of any per-transaction decision (and
        // ahead of closing-balance persistence below): a spoofed sender must hold back every
        // transaction AND every closing-balance snapshot the email claims, not just the ones
        // that also happen to fail some other check.
        //
        // Two independent checks feed it: SenderTrustEvaluator's own header-based claim
        // detection (unchanged from the legacy routing's defence), and — when a prior classify()
        // call is supplied — a check of the LLM's own claimed statement_provider against the
        // verified issuer for the sending domain. Either one flagging impersonation is enough to
        // hold every transaction in this email back for review.
        SenderTrustEvaluator.Assessment trust = senderTrustEvaluator.evaluate(from);
        String domain = ClassificationCandidate.email(from, subject, null).senderDomain();
        boolean llmClaimMismatch = classification != null
            && issuerNotSupportedByDomain(classification.statementProvider(), domain);
        boolean senderBlocked = !trust.permitsAutoImport() || llmClaimMismatch;
        String senderBlockReason = !trust.permitsAutoImport() ? trust.detail()
            : llmClaimMismatch ? "The model identified this as a " + classification.statementProvider()
                + " statement, but the sending domain " + domain + " does not belong to that issuer"
            : null;

        // Closing balances are a top-level, non-per-transaction fact — process them regardless
        // of whether this email also contains any transactions[], since a plain periodic CAS
        // statement can carry only a closing-balance summary. Never persisted from a sender that
        // failed the trust gate above, for the same reason no transaction is either.
        if (!senderBlocked) {
            persistClosingBalances(userId, root.get("closing_balances"), sourceText, gmailMessageId);
        }

        JsonNode txns = root.get("transactions");
        if (txns == null || !txns.isArray() || txns.isEmpty()) {
            audit.record(userId, EXTRACT_TASK, gmailMessageId, EXTRACT_SYSTEM, prompt, completion,
                overallConfidence, "ACCEPTED", "No transactions found");
            return Result.builder().outcome(Outcome.NOT_FINANCIAL)
                .detail("Model found no transactions").build();
        }

        int imported = 0, queuedForReview = 0, rejected = 0;
        List<String> summaries = new ArrayList<>();
        int itemIndex = 0;
        for (JsonNode tNode : txns) {
            String why = senderBlocked
                ? "Sender could not be verified — " + senderBlockReason
                : null;

            ParsedEmail pe = why == null ? toParsedEmail(tNode, sourceText) : null;
            if (why == null && pe == null) {
                why = "The model was confident but the figures for this transaction were "
                    + "incomplete, unresolved against known instruments, or unverified against "
                    + "the source text.";
            }

            if (why != null) {
                emailReviewService.enqueue(userId, gmailMessageId, itemIndex, from, subject,
                    EmailIntelResult.builder()
                        .outcome(EmailIntelResult.Outcome.REVIEW_REQUIRED)
                        .type(EmailIntelType.UNKNOWN)
                        .confidence(overallConfidence)
                        .parsed(pe)
                        .reviewReason(why)
                        .build());
                queuedForReview++;
                if (pe == null) rejected++;
                itemIndex++;
                continue;
            }

            if (overallConfidence == null || overallConfidence < minConfidence) {
                String reason = overallConfidence == null
                    ? "The extractor did not report a usable confidence score."
                    : String.format("Confidence %.2f is below the %.2f threshold required to import automatically.",
                        overallConfidence, minConfidence);
                emailReviewService.enqueue(userId, gmailMessageId, itemIndex, from, subject,
                    EmailIntelResult.builder()
                        .outcome(EmailIntelResult.Outcome.REVIEW_REQUIRED)
                        .type(EmailIntelType.UNKNOWN)
                        .confidence(overallConfidence)
                        .parsed(pe)
                        .reviewReason(reason)
                        .build());
                queuedForReview++;
                itemIndex++;
                continue;
            }

            try {
                importer.importParsedEmail(userId, user, pe, gmailMessageId, sourceText);
                summaries.add(pe.getSourceDescription());
                imported++;
            } catch (Exception e) {
                log.error("Import failed for {}: {}", pe.getSourceDescription(), e.getMessage());
                rejected++;
            }
            itemIndex++;
        }

        audit.record(userId, EXTRACT_TASK, gmailMessageId, EXTRACT_SYSTEM, prompt, completion,
            overallConfidence, imported > 0 ? "ACCEPTED" : "REVIEW_REQUIRED",
            imported + " imported, " + queuedForReview + " queued for review");

        Outcome outcome = imported > 0 ? Outcome.IMPORTED
            : queuedForReview > 0 ? Outcome.REVIEW : Outcome.NOT_FINANCIAL;
        return Result.builder()
            .outcome(outcome).imported(imported).queuedForReview(queuedForReview).rejected(rejected)
            .summaries(summaries).build();
    }

    private void enqueueWholeEmailForReview(Long userId, String gmailMessageId, String from,
                                            String subject, String reason) {
        emailReviewService.enqueue(userId, gmailMessageId, from, subject,
            EmailIntelResult.builder()
                .outcome(EmailIntelResult.Outcome.UNRESOLVED)
                .type(EmailIntelType.UNKNOWN)
                .reviewReason(reason)
                .build());
    }

    /**
     * Persists every well-formed, span-verified entry of a {@code closing_balances[]} array as a
     * {@link CasBalanceSnapshot}. An entry missing a required field, or whose evidence span
     * cannot be found in {@code sourceText}, is silently dropped rather than trusted — same rule
     * as {@link #toParsedEmail}, applied to a statement's own stated balance instead of a
     * transaction. This is intentionally not routed to the review queue the way a rejected
     * transaction is: an un-groundable closing balance is not a financial event that needs
     * booking, it is a number that just isn't usable as an independent check yet.
     */
    private void persistClosingBalances(Long userId, JsonNode closingBalances, String sourceText,
                                        String gmailMessageId) {
        if (closingBalances == null || !closingBalances.isArray() || closingBalances.isEmpty()) return;

        for (JsonNode cb : closingBalances) {
            String folio = json.str(cb, "folio");
            String schemeName = json.str(cb, "scheme_name");
            String evidence = json.str(cb, "evidence");
            BigDecimal units = json.decimal(cb, "units");
            LocalDate asOfDate = parseDate(json.str(cb, "as_of_date"));

            if (folio == null || units == null || asOfDate == null
                    || evidence == null || evidence.isBlank()) {
                continue;
            }

            List<ExtractedField> fields = new ArrayList<>();
            try {
                fields.add(ExtractedField.ofEmail("closing_balance_units", units.toPlainString(), evidence));
            } catch (IllegalArgumentException e) {
                continue;
            }
            SpanVerifier.Result verification = SpanVerifier.verify(sourceText, fields);
            if (!verification.allFound()) {
                log.info("Span verification failed for CAS closing balance ({}): dropping rather "
                    + "than trusting an ungrounded figure", verification.unfoundFields());
                continue;
            }

            // Same rule as a transaction's scheme name: an AMFI match that doesn't clear
            // MfSchemeLinkService's confidence bar is left null rather than guessed. The
            // snapshot is still recorded — it's matchable by folio alone — just without a
            // scheme code attached.
            String schemeCode = schemeName != null
                ? mfSchemeLinkService.resolveSchemeCodeByFundName(schemeName).orElse(null)
                : null;

            casBalanceSnapshotRepository.save(CasBalanceSnapshot.builder()
                .userId(userId)
                .folio(folio)
                .schemeCode(schemeCode)
                .asOfDate(asOfDate)
                .statedUnits(units)
                .sourceEmailId(gmailMessageId)
                .build());
        }
    }

    /**
     * Builds a {@link ParsedEmail} from one transaction node, or returns null when the figures
     * cannot be trusted — unresolved instrument, missing evidence, or a span-verification
     * failure. A null return always routes to review; it is never treated as "skip silently".
     */
    private ParsedEmail toParsedEmail(JsonNode t, String sourceText) {
        String instrumentType = up(json.str(t, "instrument_type"));
        String transactionType = up(json.str(t, "transaction_type"));
        String evidence = json.str(t, "evidence");
        BigDecimal amount = json.decimal(t, "amount_inr");
        LocalDate date = parseDate(json.str(t, "transaction_date"));

        // Grounding: every transaction must cite the verbatim source text its amount and date
        // came from. A field with no span is untraceable by construction (ExtractedField
        // refuses to be built without one) — treated the same as a failed verification.
        if (evidence == null || evidence.isBlank() || amount == null) return null;
        List<ExtractedField> fields = new ArrayList<>();
        try {
            fields.add(ExtractedField.ofEmail("amount_inr", amount.toPlainString(), evidence));
            // The prompt requires "evidence" to be the sentence/line the amount AND date were
            // both read from, so the date is grounded against the same span rather than trusted
            // on the model's say-so — same rule as the amount, per the "every numeric/date field
            // must be span-verified" guarantee this replaces the regex parsers with.
            if (date != null) {
                fields.add(ExtractedField.ofEmail("transaction_date", date.toString(), evidence));
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        SpanVerifier.Result verification = SpanVerifier.verify(sourceText, fields);
        if (!verification.allFound()) {
            log.info("Span verification failed for fields {} — dropping transaction rather than trusting an ungrounded figure",
                verification.unfoundFields());
            return null;
        }

        return switch (instrumentType == null ? "" : instrumentType) {
            case "MF" -> toMfParsedEmail(t, transactionType, amount, date);
            case "EQUITY" -> toEquityParsedEmail(t, transactionType, amount, date);
            case "BANK", "UPI" -> toBankOrUpiParsedEmail(t, transactionType, amount, date, sourceText);
            default -> null;
        };
    }

    private ParsedEmail toMfParsedEmail(JsonNode t, String transactionType, BigDecimal amount, LocalDate date) {
        String schemeName = json.str(t, "scheme_name");
        if (schemeName == null) return null;

        // Never book against a scheme name the AMFI master can't confidently confirm — a
        // fabricated or approximate scheme name would otherwise create (or contribute to) a
        // holding with no real fund behind it.
        Optional<String> schemeCode = mfSchemeLinkService.resolveSchemeCodeByFundName(schemeName);
        if (schemeCode.isEmpty()) {
            log.info("Could not confidently resolve MF scheme name '{}' against AMFI — routing to review", schemeName);
            return null;
        }

        ParsedEmail.Type type = switch (transactionType == null ? "" : transactionType) {
            case "REDEMPTION" -> ParsedEmail.Type.MF_REDEEM;
            case "PURCHASE", "SIP", "DIVIDEND_REINVEST" -> ParsedEmail.Type.MF_SIP;
            default -> null;
        };
        if (type == null) return null;

        return ParsedEmail.builder()
            .type(type)
            .fundName(schemeName)
            .folio(json.str(t, "folio_number"))
            .isin(json.str(t, "isin"))
            .nav(json.decimal(t, "nav"))
            .units(json.decimal(t, "units"))
            .amount(amount)
            .tradeDate(date != null ? date : LocalDate.now())
            .sourceDescription("AI-extracted " + type + ": " + schemeName + " ₹" + amount)
            .build();
    }

    private ParsedEmail toEquityParsedEmail(JsonNode t, String transactionType, BigDecimal amount, LocalDate date) {
        String symbol = resolveSymbol(json.str(t, "symbol"));
        BigDecimal price = json.decimal(t, "price");
        Integer quantity = json.integer(t, "quantity");

        if ("DIVIDEND".equals(transactionType)) {
            return ParsedEmail.builder()
                .type(ParsedEmail.Type.DIVIDEND)
                .symbol(json.str(t, "symbol"))
                .merchant(json.str(t, "merchant"))
                .amount(amount)
                .tradeDate(date != null ? date : LocalDate.now())
                .sourceDescription("AI-extracted DIVIDEND: ₹" + amount)
                .build();
        }

        ParsedEmail.Type type = "SELL".equals(transactionType) ? ParsedEmail.Type.TRADE_SELL
            : "BUY".equals(transactionType) ? ParsedEmail.Type.TRADE_BUY : null;
        if (type == null || symbol == null || price == null || quantity == null) return null;

        return ParsedEmail.builder()
            .type(type)
            .symbol(symbol)
            .quantity(quantity)
            .price(price)
            .amount(amount)
            .isin(json.str(t, "isin"))
            .dpId(json.str(t, "dp_id"))
            .clientId(json.str(t, "client_id"))
            .tradeDate(date != null ? date : LocalDate.now())
            .sourceDescription("AI-extracted " + type + ": " + symbol + " ₹" + amount)
            .build();
    }

    private ParsedEmail toBankOrUpiParsedEmail(JsonNode t, String transactionType, BigDecimal amount,
                                               LocalDate date, String sourceText) {
        String merchant = json.str(t, "merchant");
        String paymentMethod = json.str(t, "payment_method");

        if ("CREDIT".equals(transactionType)) {
            return ParsedEmail.builder()
                .type(ParsedEmail.Type.INCOME)
                .incomeSource("Other")
                .merchant(merchant)
                .paymentMethod(paymentMethod)
                .amount(amount)
                .tradeDate(date != null ? date : LocalDate.now())
                .sourceDescription("AI-extracted credit: ₹" + amount + (merchant != null ? " from " + merchant : ""))
                .build();
        }
        if ("DEBIT".equals(transactionType)) {
            // Same categorisation the deleted BankTransactionParser/UpiAppParser performed —
            // preserved here so expense categorisation keeps working exactly as before.
            String categorizationText = (merchant != null ? merchant + " " : "") + (sourceText != null ? sourceText : "");
            ExpenseCategory category = SpendCategorizer.categorize(categorizationText);
            if (category == ExpenseCategory.INVESTMENT) return null; // handled as MF/EQUITY, not an expense
            String resolvedMerchant = merchant != null ? merchant : SpendCategorizer.extractMerchant(categorizationText);
            return ParsedEmail.builder()
                .type(ParsedEmail.Type.EXPENSE)
                .category(category.getLabel())
                .merchant(resolvedMerchant)
                .paymentMethod(paymentMethod)
                .amount(amount)
                .tradeDate(date != null ? date : LocalDate.now())
                .sourceDescription("AI-extracted spend: ₹" + amount + (resolvedMerchant != null ? " at " + resolvedMerchant : ""))
                .build();
        }
        return null;
    }

    /**
     * Same guarantee as the extractors this replaces: an AI-proposed ticker is confirmed against
     * the real stock master, and an ambiguous one is refused rather than booked.
     */
    private String resolveSymbol(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        String upper = candidate.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9&-]", "");
        if (upper.isEmpty()) return null;
        List<Stock> hits;
        try {
            hits = marketDataService.searchStocks(upper);
        } catch (Exception e) {
            log.debug("Stock search failed while resolving symbol '{}': {}", candidate, e.getMessage());
            return null;
        }
        for (Stock s : hits) {
            if (upper.equalsIgnoreCase(s.getSymbol())) return s.getSymbol();
        }
        return hits.size() == 1 ? hits.get(0).getSymbol() : null;
    }

    private static String up(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static LocalDate parseDate(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when a claimed issuer isn't among the domains {@link IssuerDomainRegistry} lists for it. */
    boolean issuerNotSupportedByDomain(String claimedIssuer, String domain) {
        if (claimedIssuer == null || claimedIssuer.isBlank() || domain == null) return false;
        Optional<String> verified = IssuerDomainRegistry.issuerFor(domain);
        if (verified.isEmpty()) return false; // unknown domain — not evidence of spoofing on its own
        String v = verified.get().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String c = claimedIssuer.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return !v.contains(c) && !c.contains(v);
    }
}
