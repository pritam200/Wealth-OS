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

    private static final TransactionFingerprinter FINGERPRINTER = new TransactionFingerprinter();

    /** Provider that reads email — independent of app.llm.provider, which the other AI
     *  features use. Blank = same as app.llm.provider. */
    @Value("${app.llm.email-provider:}")
    private String emailProvider = "";

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
        "{\"instrument_type\": one of MF|EQUITY|FD|RD|BANK|UPI (FD/RD for a fixed or recurring " +
        "deposit being opened, renewed, matured or closed — never report those as a BANK debit or " +
        "credit), " +
        "\"transaction_type\": for MF one of PURCHASE|SIP|REDEMPTION|DIVIDEND_REINVEST; " +
        "for EQUITY one of BUY|SELL|DIVIDEND; for FD/RD one of OPEN|MATURITY (a renewal is OPEN " +
        "of the new deposit; a premature closure is MATURITY); for BANK/UPI one of CREDIT|DEBIT, " +
        "\"bank\": the bank holding the deposit, null unless FD/RD, " +
        "\"rate\": annual interest rate in percent, null unless FD/RD, " +
        "\"maturity_date\": ISO date yyyy-MM-dd, null unless FD/RD, " +
        "\"tenure_months\": integer, null unless RD, " +
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
        "\"transaction_date\": ISO date yyyy-MM-dd (for FD/RD OPEN, the deposit start date), " +
        "\"amount_inr\": total transaction amount (for FD OPEN the principal; for RD OPEN the " +
        "monthly instalment), " +
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
            completion = llm.completeWith(emailProvider, CLASSIFY_SYSTEM, prompt);
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
        // True when part of the source could not be read; the caller must not mark the email done.
        @Builder.Default private boolean incomplete = false;
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
        return process(userId, user, from, subject, sourceText, gmailMessageId, classification, 0);
    }

    /**
     * @param itemIndexBase first review-queue item index for this source. The email body uses 0;
     *        each PDF attachment of the same email gets its own range (see
     *        {@link #attachmentItemIndexBase}) so its review items cannot overwrite the body's —
     *        review items are keyed on (email, item index). The source's whole-email placeholder
     *        sits at {@code itemIndexBase - 1}.
     */
    public Result process(Long userId, User user, String from, String subject, String sourceText,
                          String gmailMessageId, Classification classification, int itemIndexBase) {
        if (!looksFinancial(subject, sourceText)) {
            return Result.builder().outcome(Outcome.NOT_FINANCIAL)
                .detail("No monetary signal in subject or body").build();
        }

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

        // Long sources (a month of statement lines) used to be cut at MAX_CHARS and everything
        // after the cut was silently never read. Each chunk is now extracted in turn; chunks
        // split on line boundaries and never overlap, so every line is read exactly once.
        List<String> chunks = chunk(sourceText, MAX_CHARS);
        int imported = 0, queuedForReview = 0, rejected = 0;
        List<String> summaries = new ArrayList<>();
        java.util.Map<String, Integer> occurrences = new java.util.HashMap<>();
        int itemIndex = itemIndexBase;
        List<String> unreadParts = new ArrayList<>();
        boolean anyTransactions = false;
        boolean extractorUnavailable = false;

        for (int c = 0; c < chunks.size(); c++) {
            String part = chunks.size() == 1 ? "" : " (part " + (c + 1) + " of " + chunks.size() + ")";
            String prompt = "From: " + from + "\nSubject: " + subject + part + "\n\n" + chunks.get(c);

            LlmCompletion completion;
            try {
                completion = llm.completeWith(emailProvider, EXTRACT_SYSTEM, prompt);
            } catch (LlmUnavailableException e) {
                audit.recordFailure(userId, EXTRACT_TASK, gmailMessageId, EXTRACT_SYSTEM, prompt,
                    "UNAVAILABLE", "No LLM provider available: " + e.getMessage());
                extractorUnavailable = true;
                unreadParts.add(chunks.size() == 1 ? "the email" : "part " + (c + 1) + " of " + chunks.size());
                continue;
            }

            Optional<JsonNode> parsedJson = json.parse(completion.getText());
            if (parsedJson.isEmpty()) {
                audit.record(userId, EXTRACT_TASK, gmailMessageId, EXTRACT_SYSTEM, prompt, completion, null,
                    "PARSE_FAILED", "Model output was not valid JSON");
                unreadParts.add(chunks.size() == 1 ? "the email" : "part " + (c + 1) + " of " + chunks.size());
                continue;
            }

            JsonNode root = parsedJson.get();
            Double overallConfidence = json.confidence(root, "confidence");

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
                continue;
            }
            anyTransactions = true;
            // Rail references are harvested from the source text, so they identify a transaction
            // only when the source describes exactly one. For a statement the text is withheld and
            // lines are matched on content alone (see ParsedEmailImporter.attributableReference).
            String referenceText = chunks.size() == 1 && txns.size() == 1 ? sourceText : null;

            int chunkImported = 0, chunkQueued = 0;
            for (JsonNode tNode : txns) {
                String why = senderBlocked
                    ? "Sender could not be verified — " + senderBlockReason
                    : null;

                ParsedEmail pe = null;
                if (why == null) {
                    Extraction extraction = toParsedEmail(tNode, sourceText);
                    pe = extraction.parsed();
                    why = extraction.reason();
                }
                if (pe != null) {
                    // Two identical lines in one statement are two transactions; numbering them
                    // keeps the second from being deduplicated against the first, and keeps each
                    // line's identity stable when the same email is re-synced.
                    int n = occurrences.merge(occurrenceKey(pe), 1, Integer::sum) - 1;
                    pe.setOccurrenceInSource(n);
                }

                if (why != null) {
                    enqueueItemForReview(userId, gmailMessageId, itemIndex, from, subject, overallConfidence, pe, why);
                    chunkQueued++;
                    if (pe == null) rejected++;
                    itemIndex++;
                    continue;
                }

                if (overallConfidence == null || overallConfidence < minConfidence) {
                    String reason = overallConfidence == null
                        ? "The extractor did not report a usable confidence score."
                        : String.format("Confidence %.2f is below the %.2f threshold required to import automatically.",
                            overallConfidence, minConfidence);
                    enqueueItemForReview(userId, gmailMessageId, itemIndex, from, subject, overallConfidence, pe, reason);
                    chunkQueued++;
                    itemIndex++;
                    continue;
                }

                // Every failure here is queued for review. Only logging it used to lose the
                // transaction for good whenever another item in the same email imported, because
                // the email was then marked IMPORTED and never looked at again.
                try {
                    importer.importParsedEmail(userId, user, pe, gmailMessageId, referenceText);
                    summaries.add(pe.getSourceDescription());
                    chunkImported++;
                } catch (ImportRejectedException e) {
                    enqueueItemForReview(userId, gmailMessageId, itemIndex, from, subject, overallConfidence, pe, e.getMessage());
                    chunkQueued++;
                    rejected++;
                } catch (Exception e) {
                    log.error("Import failed for {}: {}", pe.getSourceDescription(), e.getMessage(), e);
                    enqueueItemForReview(userId, gmailMessageId, itemIndex, from, subject, overallConfidence, pe,
                        "Could not be saved automatically (" + e.getMessage() + ") — accept to retry.");
                    chunkQueued++;
                    rejected++;
                }
                itemIndex++;
            }
            imported += chunkImported;
            queuedForReview += chunkQueued;

            audit.record(userId, EXTRACT_TASK, gmailMessageId, EXTRACT_SYSTEM, prompt, completion,
                overallConfidence, chunkImported > 0 ? "ACCEPTED" : "REVIEW_REQUIRED",
                chunkImported + " imported, " + chunkQueued + " queued for review");
        }

        if (!unreadParts.isEmpty()) {
            // Some or all of the source could not be read. The email is reported as incomplete so
            // the sync retries it (already-booked lines are recognised and not booked twice), and
            // a placeholder in the review queue makes the gap visible in the meantime.
            String reason = (extractorUnavailable ? "The extractor was unavailable" : "The extractor returned unreadable output")
                + " for " + String.join(", ", unreadParts) + ", so it was left unprocessed — it will be retried on the next sync.";
            enqueueWholeEmailForReview(userId, gmailMessageId, itemIndexBase - 1, from, subject, reason);
            queuedForReview++;
            return Result.builder()
                .outcome(extractorUnavailable && imported == 0 ? Outcome.UNAVAILABLE : Outcome.REVIEW)
                .imported(imported).queuedForReview(queuedForReview).rejected(rejected)
                .summaries(summaries).incomplete(true).detail(reason).build();
        }
        emailReviewService.clearWholeEmailPlaceholder(userId, gmailMessageId, itemIndexBase - 1);

        if (!anyTransactions) {
            return Result.builder().outcome(Outcome.NOT_FINANCIAL)
                .detail("Model found no transactions").build();
        }

        Outcome outcome = imported > 0 ? Outcome.IMPORTED
            : queuedForReview > 0 ? Outcome.REVIEW : Outcome.NOT_FINANCIAL;
        return Result.builder()
            .outcome(outcome).imported(imported).queuedForReview(queuedForReview).rejected(rejected)
            .summaries(summaries).build();
    }

    /**
     * Splits text into pieces of at most {@code max} characters, preferring line breaks, then
     * sentence/field separators, then spaces, so a transaction line is not cut in half. Pieces
     * never overlap: overlapping would make a line near a boundary appear twice and be booked
     * twice as two "identical lines".
     */
    /** Review item range for one PDF attachment: stable across retries (derived from the file
     *  name) and well clear of the body's 0, 1, 2, … */
    public static int attachmentItemIndexBase(String filename) {
        return 1_000_000 + Math.floorMod(filename == null ? 0 : filename.hashCode(), 1000) * 1000;
    }

    static List<String> chunk(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.length() <= max) {
            out.add(text == null ? "" : text);
            return out;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + max, text.length());
            if (end < text.length()) {
                int cut = text.lastIndexOf('\n', end);
                if (cut <= start + max / 2) cut = Math.max(text.lastIndexOf(". ", end), text.lastIndexOf(" | ", end));
                if (cut <= start + max / 2) cut = text.lastIndexOf(' ', end);
                if (cut > start + max / 2) end = cut + 1;
            }
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    /**
     * What makes two lines of one email "the same line". For debits and credits it is exactly
     * what {@code ParsedEmailImporter}'s same-email check counts — direction, amount and date —
     * so the numbering and that check always agree, and a re-read that names the merchant
     * slightly differently still lines up. Other types use the full content fingerprint.
     */
    private static String occurrenceKey(ParsedEmail pe) {
        String direction = switch (pe.getType() == null ? ParsedEmail.Type.UNKNOWN : pe.getType()) {
            case EXPENSE -> "DEBIT";
            case INCOME, DIVIDEND -> "CREDIT";
            default -> null;
        };
        if (direction == null) return FINGERPRINTER.fingerprint(pe);
        return direction + "|" + (pe.getAmount() == null ? "" : pe.getAmount().stripTrailingZeros().toPlainString())
            + "|" + pe.getTradeDate();
    }

    private void enqueueItemForReview(Long userId, String gmailMessageId, int itemIndex, String from,
                                      String subject, Double confidence, ParsedEmail pe, String reason) {
        emailReviewService.enqueue(userId, gmailMessageId, itemIndex, from, subject,
            EmailIntelResult.builder()
                .outcome(EmailIntelResult.Outcome.REVIEW_REQUIRED)
                .type(EmailIntelType.UNKNOWN)
                .confidence(confidence)
                .parsed(pe)
                .reviewReason(reason)
                .build());
    }

    private void enqueueWholeEmailForReview(Long userId, String gmailMessageId, int itemIndex, String from,
                                            String subject, String reason) {
        emailReviewService.enqueue(userId, gmailMessageId, itemIndex, from, subject,
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

    /** Either a bookable transaction or the specific reason it has to go to review instead. */
    record Extraction(ParsedEmail parsed, String reason) {
        static Extraction ok(ParsedEmail pe) { return new Extraction(pe, null); }
        static Extraction review(String reason) { return new Extraction(null, reason); }
        static Extraction orReview(ParsedEmail pe, String reason) { return pe != null ? ok(pe) : review(reason); }
    }

    /**
     * Builds a {@link ParsedEmail} from one transaction node, or a review reason when the figures
     * cannot be trusted — unresolved instrument, missing evidence or date, or a span-verification
     * failure. A review result is always queued; it is never treated as "skip silently".
     */
    Extraction toParsedEmail(JsonNode t, String sourceText) {
        String instrumentType = up(json.str(t, "instrument_type"));
        String transactionType = up(json.str(t, "transaction_type"));
        String evidence = json.str(t, "evidence");
        BigDecimal amount = json.decimal(t, "amount_inr");
        LocalDate date = parseDate(json.str(t, "transaction_date"));

        // Grounding: every transaction must cite the verbatim source text its amount and date
        // came from. A field with no span is untraceable by construction (ExtractedField
        // refuses to be built without one) — treated the same as a failed verification.
        if (evidence == null || evidence.isBlank()) {
            return Extraction.review("The extractor did not cite the source line for this transaction.");
        }
        if (amount == null) {
            return Extraction.review("No amount could be read for this transaction.");
        }
        // Defaulting to today mis-dated every historical transaction on a full resync — booked
        // in the wrong month, wrong cash-flow and wrong tax year. Never guess a date.
        if (date == null) {
            return Extraction.review("No transaction date could be read for this transaction.");
        }
        List<ExtractedField> fields = new ArrayList<>();
        try {
            fields.add(ExtractedField.ofEmail("amount_inr", amount.toPlainString(), evidence));
            // The prompt requires "evidence" to be the sentence/line the amount AND date were
            // both read from, so the date is grounded against the same span rather than trusted
            // on the model's say-so — same rule as the amount.
            fields.add(ExtractedField.ofEmail("transaction_date", date.toString(), evidence));
        } catch (IllegalArgumentException e) {
            return Extraction.review("The cited source line could not be used to verify the figures.");
        }
        SpanVerifier.Result verification = SpanVerifier.verify(sourceText, fields);
        if (!verification.allFound()) {
            log.info("Span verification failed for fields {} — dropping transaction rather than trusting an ungrounded figure",
                verification.unfoundFields());
            return Extraction.review("The figures for " + verification.unfoundFields()
                + " could not be found in the email text, so they were not trusted.");
        }

        return switch (instrumentType == null ? "" : instrumentType) {
            case "MF" -> Extraction.orReview(toMfParsedEmail(t, transactionType, amount, date),
                "The mutual fund scheme could not be matched to an AMFI scheme, or the transaction type was unclear.");
            case "EQUITY" -> Extraction.orReview(toEquityParsedEmail(t, transactionType, amount, date),
                "The stock symbol, quantity or price could not be confirmed.");
            case "FD", "RD" -> toDepositParsedEmail(t, instrumentType, transactionType, amount, date);
            case "BANK", "UPI" -> toBankOrUpiParsedEmail(t, transactionType, amount, date, evidence);
            default -> Extraction.review("The kind of transaction (" + instrumentType + ") could not be determined.");
        };
    }

    private Extraction toDepositParsedEmail(JsonNode t, String instrumentType, String transactionType,
                                            BigDecimal amount, LocalDate date) {
        String bank = json.str(t, "bank");
        if (!"OPEN".equals(transactionType)) {
            // A maturity/closure payout is mostly your own principal coming back — booking it as
            // income would overstate income by the whole principal.
            return Extraction.review((instrumentType.equals("FD") ? "Fixed" : "Recurring")
                + " deposit maturity/closure" + (bank != null ? " at " + bank : "")
                + " for ₹" + amount.toPlainString() + " — the principal is not income. Confirm the interest portion if you want it recorded.");
        }
        if (bank == null) {
            return Extraction.review("A new deposit was found but the bank could not be read.");
        }
        BigDecimal rate = json.decimal(t, "rate");
        if (rate == null) {
            return Extraction.review("A new deposit at " + bank + " was found but its interest rate could not be read.");
        }
        if ("FD".equals(instrumentType)) {
            return Extraction.ok(ParsedEmail.builder()
                .type(ParsedEmail.Type.FD_OPEN)
                .bank(bank)
                .principal(amount)
                .rate(rate)
                .startDate(date)
                .maturityDate(parseDate(json.str(t, "maturity_date")))
                .sourceDescription("AI-extracted FD: " + bank + " ₹" + amount + " @ " + rate + "%")
                .build());
        }
        Integer tenure = json.integer(t, "tenure_months");
        if (tenure == null || tenure <= 0) {
            return Extraction.review("A new recurring deposit at " + bank + " was found but its tenure could not be read.");
        }
        return Extraction.ok(ParsedEmail.builder()
            .type(ParsedEmail.Type.RD_OPEN)
            .bank(bank)
            .monthlyAmount(amount)
            .rate(rate)
            .tenureMonths(tenure)
            .startDate(date)
            .sourceDescription("AI-extracted RD: " + bank + " ₹" + amount + "/mo @ " + rate + "%")
            .build());
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
            .tradeDate(date)
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
                .tradeDate(date)
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
            .tradeDate(date)
            .sourceDescription("AI-extracted " + type + ": " + symbol + " ₹" + amount)
            .build();
    }

    // Credits that are money you already had moving around, not income. Matched against the
    // transaction's own line only.
    private static final String[][] NON_INCOME_CREDITS = {
        {"refund", "A refund of an earlier purchase — not income."},
        {"reversal", "A reversal of an earlier debit — not income."},
        {"reversed", "A reversal of an earlier debit — not income."},
        {"chargeback", "A chargeback of an earlier debit — not income."},
        {"payment received", "A credit card bill payment received on the card — a transfer, not income."},
        {"thank you for your payment", "A credit card bill payment received on the card — a transfer, not income."},
        {"fixed deposit", "A fixed deposit payout — the principal is not income."},
        {"recurring deposit", "A recurring deposit payout — the principal is not income."},
        {"maturity", "A deposit maturity payout — the principal is not income."},
        {"self transfer", "A transfer between your own accounts — not income."},
        {"own account", "A transfer between your own accounts — not income."},
    };

    private Extraction toBankOrUpiParsedEmail(JsonNode t, String transactionType, BigDecimal amount,
                                              LocalDate date, String evidence) {
        String merchant = json.str(t, "merchant");
        String paymentMethod = json.str(t, "payment_method");
        // Categorised from this transaction's own line, never the whole email: a statement's full
        // text contains every other line's merchants plus boilerplate footers ("mutual fund
        // investments are subject to market risks"), which used to give all 40 lines of a card
        // statement whichever category's keyword happened to appear first anywhere in it.
        String lineText = (merchant != null ? merchant + " " : "") + evidence;

        if ("CREDIT".equals(transactionType)) {
            String lower = lineText.toLowerCase(Locale.ROOT);
            for (String[] rule : NON_INCOME_CREDITS) {
                if (lower.contains(rule[0])) return Extraction.review(rule[1] + " (₹" + amount.toPlainString() + ")");
            }
            return Extraction.ok(ParsedEmail.builder()
                .type(ParsedEmail.Type.INCOME)
                .incomeSource("Other")
                .merchant(merchant)
                .paymentMethod(paymentMethod)
                .amount(amount)
                .tradeDate(date)
                .sourceDescription("AI-extracted credit: ₹" + amount + (merchant != null ? " from " + merchant : ""))
                .build());
        }
        if ("DEBIT".equals(transactionType)) {
            ExpenseCategory category = SpendCategorizer.categorize(lineText);
            if (category == ExpenseCategory.INVESTMENT) {
                // Booking it as spend would double-count the investment once the AMC/RTA
                // confirmation books the MF/stock side; routed to review so it isn't lost if that
                // confirmation never arrives.
                return Extraction.review("A bank debit for an investment (₹" + amount.toPlainString()
                    + ") — booked from the fund/broker confirmation, not as spending. Reject this if that confirmation was imported.");
            }
            String resolvedMerchant = merchant != null ? merchant : SpendCategorizer.extractMerchant(lineText);
            return Extraction.ok(ParsedEmail.builder()
                .type(ParsedEmail.Type.EXPENSE)
                .category(category.getLabel())
                .merchant(resolvedMerchant)
                .paymentMethod(paymentMethod)
                .amount(amount)
                .tradeDate(date)
                .sourceDescription("AI-extracted spend: ₹" + amount + (resolvedMerchant != null ? " at " + resolvedMerchant : ""))
                .build());
        }
        return Extraction.review("It was unclear whether this was money in or money out.");
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
