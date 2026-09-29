package com.marketai.gmail.service;

import com.marketai.ai.prompt.PromptLibrary;

import com.marketai.ai.llm.LlmTask;

import com.marketai.ai.llm.LlmService;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.intel.EmailIntelResult;
import com.marketai.ai.intel.EmailIntelType;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.ai.review.service.EmailReviewService;
import com.marketai.auth.entity.User;
import com.marketai.document.classify.ClassificationCandidate;
import com.marketai.document.classify.IssuerDomainRegistry;
import com.marketai.document.classify.SenderTrustEvaluator;
import com.marketai.document.extract.ExtractedField;
import com.marketai.document.extract.SpanVerifier;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.gmail.ledger.EmailFinancialEvent;
import com.marketai.gmail.ledger.EventState;
import com.marketai.gmail.ledger.FinancialEventLedger;
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

    private final LlmService llm;
    private final LlmJsonParser json;
    private final AiAuditService audit;
    private final MarketDataService marketDataService;
    private final MfSchemeLinkService mfSchemeLinkService;
    private final SenderTrustEvaluator senderTrustEvaluator;
    private final EmailReviewService emailReviewService;
    private final ParsedEmailImporter importer;
    private final CasBalanceSnapshotRepository casBalanceSnapshotRepository;
    private final FinancialEventLedger ledger;

    private static final TransactionFingerprinter FINGERPRINTER = new TransactionFingerprinter();

    @Value("${app.llm.min-confidence:0.85}")
    private double minConfidence;

    private static final int MAX_CHARS = 8000;
    private static final String CLASSIFY_TASK = "EMAIL_LLM_CLASSIFY";
    private static final String EXTRACT_TASK = "EMAIL_LLM_EXTRACT";

    // Deliberately wide: this screen only decides whether a plain email body is worth an LLM
    // call. Missing a financial email here would lose it with no trace, while letting a
    // newsletter through costs one call that finds nothing.
    private static final String[] MONEY_SIGNALS = {
        "₹", "rs.", "rs ", "inr", "debited", "credited", "spent", "paid", "purchase",
        "sip", "mutual fund", "nav", "folio", "fd", "rd", "salary", "dividend", "upi",
        "transaction", "payment", "emi", "invested", "redeemed", "withdrawn", "balance",
        "transfer", "neft", "imps", "rtgs", "bought", "sold", "buy", "sell", "trade",
        "contract note", "order", "shares", "units", "brokerage", "settlement", "demat",
        "maturity", "interest", "refund", "statement", "password",
        "$", "€", "£", "usd", "eur", "gbp", "aed", "sgd",
        "invoice", "receipt", "bill", "premium", "cashback", "reward", "loan", "tax", "gst", "tds",
        "policy", "a/c", "acct", "account", "card", "wallet", "charge", "fee", "reversal", "reversed",
        "subscription", "renewal", "booking", "reimburse", "payout", "deposit", "allotment",
        "bonus", "split", "buyback", "rights issue", "merger", "demerger", "redemption", "switch",
    };

    // --- Call 1: classify + password hint -------------------------------------------------

    public record Classification(boolean financialStatement, String statementProvider,
                                 PasswordStrategy passwordHintType) {
        static Classification none() { return new Classification(false, null, null); }
    }

    public boolean looksFinancial(String subject, String body) {
        return looksFinancial(null, subject, body);
    }

    /** As {@link #looksFinancial(String, String)}; mail from a known bank, broker, AMC or RTA always passes. */
    public boolean looksFinancial(String from, String subject, String body) {
        if (from != null) {
            String domain = ClassificationCandidate.email(from, subject, null).senderDomain();
            if (domain != null && IssuerDomainRegistry.issuerFor(domain).isPresent()) return true;
        }
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
        return classify(null, null, from, subject, body);
    }

    /** As {@link #classify(String, String, String)}, with the audit record attributed to the
     *  user and email it was run for (it used to be saved with neither). */
    public Classification classify(Long userId, String gmailMessageId, String from, String subject, String body) {
        if (!looksFinancial(from, subject, body)) return Classification.none();

        String text = (subject == null ? "" : subject) + "\n" + (body == null ? "" : body);
        String truncated = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        String prompt = "From: " + from + "\nSubject: " + subject + "\n\n" + truncated;

        LlmCompletion completion;
        try {
            completion = llm.complete(LlmTask.EMAIL_CLASSIFICATION, PromptLibrary.EMAIL_CLASSIFICATION, prompt);
        } catch (LlmUnavailableException e) {
            audit.recordFailure(userId, CLASSIFY_TASK, gmailMessageId, PromptLibrary.EMAIL_CLASSIFICATION.system(), prompt,
                "UNAVAILABLE", "No LLM provider available: " + e.getMessage());
            return Classification.none();
        } catch (Exception e) {
            log.warn("Classification call failed for {}: {}", gmailMessageId, e.getMessage());
            audit.recordFailure(userId, CLASSIFY_TASK, gmailMessageId, PromptLibrary.EMAIL_CLASSIFICATION.system(), prompt,
                "UNAVAILABLE", "Classification call failed: " + e.getMessage());
            return Classification.none();
        }

        Optional<JsonNode> parsed = json.parse(completion.getText());
        if (parsed.isEmpty()) {
            audit.record(userId, CLASSIFY_TASK, gmailMessageId, PromptLibrary.EMAIL_CLASSIFICATION.system(), prompt, completion, null,
                "PARSE_FAILED", "Model output was not valid JSON");
            return Classification.none();
        }
        JsonNode node = parsed.get();
        boolean isStatement = node.path("is_financial_statement").asBoolean(false);
        String provider = json.str(node, "statement_provider");
        PasswordStrategy strategy = mapPasswordHint(json.str(node, "password_hint_type"));

        audit.record(userId, CLASSIFY_TASK, gmailMessageId, PromptLibrary.EMAIL_CLASSIFICATION.system(), prompt, completion, null,
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

    /** The per-document record of what {@code r} produced (see {@link DocumentCounts}). */
    public static com.marketai.gmail.entity.DocumentCounts countsOf(Result r) {
        String outcome;
        if (r.getOutcome() == Outcome.NOT_FINANCIAL && r.getExtracted() == 0 && !r.isIncomplete()) {
            outcome = com.marketai.gmail.entity.DocumentCounts.NO_TRANSACTION;
        } else if (r.isIncomplete()) {
            outcome = r.getImported() + r.getDuplicates() + r.getResolved() > 0
                ? com.marketai.gmail.entity.DocumentCounts.PARTIAL_SUCCESS
                : com.marketai.gmail.entity.DocumentCounts.FAILED;
        } else if (r.getQueuedForReview() > 0 || r.getConflicts() > 0 || "MISMATCHED".equals(r.getTotalsCheck())) {
            outcome = com.marketai.gmail.entity.DocumentCounts.RECONCILIATION_REQUIRED;
        } else if (r.getExtracted() > 0 && r.getImported() + r.getDuplicates() + r.getResolved() == 0) {
            outcome = com.marketai.gmail.entity.DocumentCounts.FAILED;
        } else {
            outcome = com.marketai.gmail.entity.DocumentCounts.SUCCESS;
        }
        return com.marketai.gmail.entity.DocumentCounts.builder()
            .extracted(r.getExtracted()).imported(r.getImported()).duplicates(r.getDuplicates())
            .conflicts(r.getConflicts()).needsReview(r.getQueuedForReview()).failed(r.getRejected())
            .resolved(r.getResolved())
            .totalsCheck(r.getTotalsCheck())
            .totalsDetail(r.getTotalsDetail() != null && r.getTotalsDetail().length() > 500
                ? r.getTotalsDetail().substring(0, 500) : r.getTotalsDetail())
            .outcome(outcome).build();
    }

    @Data
    @Builder
    public static class Result {
        private Outcome outcome;
        @Builder.Default private int imported = 0;
        /** Items recognised as already recorded — accounted for, but not new. */
        @Builder.Default private int duplicates = 0;
        /** Items quoting the same payment reference as a recorded one with different details —
         *  not booked; flagged on the original for a person to resolve. */
        @Builder.Default private int conflicts = 0;
        /** Transaction lines the model reported, before any validation. */
        @Builder.Default private int extracted = 0;
        /** MATCHED / MISMATCHED against the statement's own stated totals; null when it states none. */
        private String totalsCheck;
        private String totalsDetail;
        @Builder.Default private int queuedForReview = 0;
        @Builder.Default private int rejected = 0;
        /** Events that need no record, for a stated reason (a failed or cancelled payment). */
        @Builder.Default private int resolved = 0;
        @Builder.Default private List<String> summaries = new ArrayList<>();
        // True when part of the source could not be read; the caller must not mark the email done.
        @Builder.Default private boolean incomplete = false;
        private String detail;
        private String matchedProvider;
    }

    /**
     * One result for several documents of the same email (its body and its text attachments),
     * decided the same way a single document's is.
     */
    public static Result merge(Result a, Result b) {
        if (a == null) return b;
        if (b == null) return a;
        int imported = a.getImported() + b.getImported();
        int duplicates = a.getDuplicates() + b.getDuplicates();
        int conflicts = a.getConflicts() + b.getConflicts();
        int resolved = a.getResolved() + b.getResolved();
        int queued = a.getQueuedForReview() + b.getQueuedForReview();
        boolean incomplete = a.isIncomplete() || b.isIncomplete();
        boolean unavailable = a.getOutcome() == Outcome.UNAVAILABLE || b.getOutcome() == Outcome.UNAVAILABLE;
        Outcome outcome = incomplete ? (unavailable && imported == 0 ? Outcome.UNAVAILABLE : Outcome.REVIEW)
            : imported > 0 || ((duplicates + conflicts + resolved) > 0 && queued == 0) ? Outcome.IMPORTED
            : queued > 0 ? Outcome.REVIEW : Outcome.NOT_FINANCIAL;
        String totalsCheck = "MISMATCHED".equals(a.getTotalsCheck()) || "MISMATCHED".equals(b.getTotalsCheck()) ? "MISMATCHED"
            : a.getTotalsCheck() != null ? a.getTotalsCheck() : b.getTotalsCheck();
        List<String> summaries = new ArrayList<>(a.getSummaries());
        summaries.addAll(b.getSummaries());
        return Result.builder().outcome(outcome)
            .imported(imported).duplicates(duplicates).conflicts(conflicts).resolved(resolved)
            .extracted(a.getExtracted() + b.getExtracted()).queuedForReview(queued)
            .rejected(a.getRejected() + b.getRejected()).incomplete(incomplete)
            .totalsCheck(totalsCheck).totalsDetail(joinNonNull(a.getTotalsDetail(), b.getTotalsDetail()))
            .detail(joinNonNull(a.getDetail(), b.getDetail()))
            .summaries(summaries).build();
    }

    private static String joinNonNull(String x, String y) {
        if (x == null) return y;
        if (y == null) return x;
        return x + "; " + y;
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
        return process(userId, user, from, subject, sourceText, gmailMessageId, classification, itemIndexBase, null, null);
    }

    /**
     * @param attachmentId  the PDF attachment {@code sourceText} came from; null for an email body
     * @param documentHash  SHA-256 of the attachment's text; recorded against every event read
     *                      from it, so each can be traced to its document
     */
    public Result process(Long userId, User user, String from, String subject, String sourceText,
                          String gmailMessageId, Classification classification, int itemIndexBase,
                          String attachmentId, String documentHash) {
        return process(userId, user, from, subject, sourceText, gmailMessageId, classification, itemIndexBase,
            attachmentId, documentHash, null);
    }

    /** @param extractionMethod recorded on each event; null means email body or PDF by attachment */
    public Result process(Long userId, User user, String from, String subject, String sourceText,
                          String gmailMessageId, Classification classification, int itemIndexBase,
                          String attachmentId, String documentHash, String extractionMethod) {
        return process(userId, user, from, subject, sourceText, gmailMessageId, classification, itemIndexBase,
            new SourceDoc(attachmentId, null, documentHash, extractionMethod));
    }

    /**
     * The document {@code sourceText} came from. All null for an email body.
     *
     * @param extractionMethod recorded on each event; null means email body, or PDF when there is an attachment
     */
    public record SourceDoc(String attachmentId, String name, String documentHash, String extractionMethod) {
        public static final SourceDoc BODY = new SourceDoc(null, null, null, null);

        boolean isDocument() { return attachmentId != null || extractionMethod != null; }
    }

    public Result process(Long userId, User user, String from, String subject, String sourceText,
                          String gmailMessageId, Classification classification, int itemIndexBase, SourceDoc src) {
        if (src == null) src = SourceDoc.BODY;
        String attachmentId = src.attachmentId();
        String documentHash = src.documentHash();
        String extractionMethod = src.extractionMethod();
        // A document someone attached is always read. Only a plain body is screened first, and
        // the screen is wide on purpose: it lets through anything naming money, an account, a
        // card, a bill or a known issuer, and the reason is kept when it does not.
        if (!src.isDocument() && !looksFinancial(from, subject, sourceText)) {
            return Result.builder().outcome(Outcome.NOT_FINANCIAL)
                .detail("Screened out before extraction: no amount, account, payment, bill or known "
                    + "financial sender in the subject or body").build();
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
        String statementProvider = classification != null && classification.statementProvider() != null
            ? classification.statementProvider() : domain;

        // Long sources (a month of statement lines) used to be cut at MAX_CHARS and everything
        // after the cut was silently never read. Each chunk is now extracted in turn; chunks
        // split on line boundaries and never overlap, so every line is read exactly once.
        List<String> chunks = chunk(sourceText, MAX_CHARS);
        java.time.LocalDateTime readStartedAt = java.time.LocalDateTime.now();
        // An attachment or a transcribed scan is a document; a plain alert body is routine email.
        // Both run the same prompt and the same validation below — only the configured model differs.
        LlmTask extractTask = src.isDocument() ? LlmTask.DOCUMENT_EXTRACTION : LlmTask.EMAIL_EXTRACTION;
        int imported = 0, duplicates = 0, conflicts = 0, extracted = 0, queuedForReview = 0, rejected = 0, resolved = 0;
        StatementTotals totals = new StatementTotals();
        List<String> summaries = new ArrayList<>();
        java.util.Map<String, Integer> occurrences = new java.util.HashMap<>();
        java.util.Map<String, Integer> rawOccurrences = new java.util.HashMap<>();
        int itemIndex = itemIndexBase;
        List<String> unreadParts = new ArrayList<>();
        boolean anyTransactions = false;
        boolean extractorUnavailable = false;
        LlmCompletion lastCompletion = null;

        for (int c = 0; c < chunks.size(); c++) {
            String part = chunks.size() == 1 ? "" : " (part " + (c + 1) + " of " + chunks.size() + ")";
            String prompt = "From: " + from + "\nSubject: " + subject + part + "\n\n" + chunks.get(c);

            LlmCompletion completion;
            try {
                completion = llm.complete(extractTask, PromptLibrary.TRANSACTION_EXTRACTION, prompt);
            } catch (LlmUnavailableException e) {
                audit.recordFailure(userId, EXTRACT_TASK, gmailMessageId, PromptLibrary.TRANSACTION_EXTRACTION.system(), prompt,
                    "UNAVAILABLE", "No LLM provider available: " + e.getMessage());
                extractorUnavailable = true;
                unreadParts.add(chunks.size() == 1 ? "the " + (src.isDocument() ? "document" : "email") : "part " + (c + 1) + " of " + chunks.size());
                continue;
            }
            lastCompletion = completion;

            Optional<JsonNode> parsedJson = json.parse(completion.getText());
            if (parsedJson.isEmpty()) {
                audit.record(userId, EXTRACT_TASK, gmailMessageId, PromptLibrary.TRANSACTION_EXTRACTION.system(), prompt, completion, null,
                    "PARSE_FAILED", "Model output was not valid JSON");
                unreadParts.add(chunks.size() == 1 ? "the " + (src.isDocument() ? "document" : "email") : "part " + (c + 1) + " of " + chunks.size());
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

            totals.readStated(root.get("statement_totals"), sourceText);
            JsonNode txns = root.get("transactions");
            if (txns != null && txns.isArray()) totals.addLines(txns);
            if (txns == null || !txns.isArray() || txns.isEmpty()) {
                audit.record(userId, EXTRACT_TASK, gmailMessageId, PromptLibrary.TRANSACTION_EXTRACTION.system(), prompt, completion,
                    overallConfidence, "ACCEPTED", "No transactions found");
                continue;
            }
            anyTransactions = true;
            // Rail references are harvested from the source text, so they identify a transaction
            // only when the source describes exactly one. For a statement the text is withheld and
            // lines are matched on content alone (see ParsedEmailImporter.attributableReference).
            String referenceText = chunks.size() == 1 && txns.size() == 1 ? sourceText : null;

            int chunkImported = 0, chunkQueued = 0;
            extracted += txns.size();
            for (JsonNode tNode : txns) {
                // Every event the model reported gets a ledger row, whatever happens to it below.
                String rawKey = rawEventKey(tNode);
                int rawN = rawOccurrences.merge(rawKey, 1, Integer::sum) - 1;
                EmailFinancialEvent event = eventFrom(tNode, userId, gmailMessageId,
                    "d" + itemIndexBase + ":" + sha256(rawKey).substring(0, 24) + "#" + rawN,
                    itemIndex, src, statementProvider, completion, overallConfidence);

                String why = senderBlocked
                    ? "Sender could not be verified — " + senderBlockReason
                    : null;

                ParsedEmail pe = null;
                Extraction extraction = null;
                if (why == null) {
                    extraction = toParsedEmail(tNode, sourceText);
                    pe = extraction.parsed();
                    why = extraction.reason();
                }
                if (pe != null) {
                    pe.setSourceAttachmentId(attachmentId);
                    pe.setSourceDocumentHash(documentHash);
                    pe.setExtractionMethod(extractionMethod != null ? extractionMethod : attachmentId != null
                        ? com.marketai.common.ledger.Provenance.PDF_LLM : com.marketai.common.ledger.Provenance.EMAIL_LLM);
                    pe.setExtractionConfidence(overallConfidence);
                    pe.setExtractionVersion(completion.extractionVersion());
                    pe.setReadStartedAt(readStartedAt);
                    // Two identical lines in one statement are two transactions; numbering them
                    // keeps the second from being deduplicated against the first, and keeps each
                    // line's identity stable when the same email is re-synced.
                    int n = occurrences.merge(occurrenceKey(pe), 1, Integer::sum) - 1;
                    pe.setOccurrenceInSource(n);
                }

                if (extraction != null && extraction.resolved()) {
                    // A failed or cancelled payment: nothing to book, and the reason is kept.
                    ledger.record(event.toBuilder().state(EventState.RESOLVED).reason(why)
                        .validationStatus(VERIFIED).dedupStatus(NOT_CHECKED).build());
                    summaries.add("Not booked — " + why);
                    resolved++;
                    itemIndex++;
                    continue;
                }

                if (why != null) {
                    enqueueItemForReview(userId, gmailMessageId, itemIndex, from, subject, overallConfidence, pe, why);
                    ledger.record(event.toBuilder().state(EventState.REQUIRES_REVIEW).reason(why)
                        .validationStatus(senderBlocked ? NOT_CHECKED : pe == null ? FAILED : VERIFIED)
                        .dedupStatus(NOT_CHECKED).build());
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
                    ledger.record(event.toBuilder().state(EventState.REQUIRES_REVIEW).reason(reason)
                        .validationStatus(VERIFIED).dedupStatus(NOT_CHECKED).build());
                    chunkQueued++;
                    itemIndex++;
                    continue;
                }

                // Every failure here is queued for review. Only logging it used to lose the
                // transaction for good whenever another item in the same email imported, because
                // the email was then marked IMPORTED and never looked at again.
                try {
                    ParsedEmailImporter.ImportOutcome booked =
                        importer.importParsedEmail(userId, user, pe, gmailMessageId, referenceText);
                    if (booked == ParsedEmailImporter.ImportOutcome.DUPLICATE) {
                        // Counted, never reported as a fresh import: a sync that re-reads a
                        // statement it already booked says "N already recorded", not "N imported".
                        duplicates++;
                        summaries.add("Already recorded: " + pe.getSourceDescription());
                        ledger.record(event.toBuilder().state(EventState.DUPLICATE_OF_EXISTING)
                            .reason("Already on record — " + pe.getSourceDescription())
                            .validationStatus(VERIFIED).dedupStatus("DUPLICATE").build());
                    } else if (booked == ParsedEmailImporter.ImportOutcome.CONFLICT) {
                        conflicts++;
                        summaries.add("Conflicts with a recorded transaction: " + pe.getSourceDescription());
                        ledger.record(event.toBuilder().state(EventState.RECONCILIATION_REQUIRED)
                            .reason("Quotes the same payment reference as a recorded transaction, with different details. "
                                + "The recorded one was kept; compare them and mark this resolved.")
                            .validationStatus(VERIFIED).dedupStatus("CONFLICT").build());
                    } else {
                        summaries.add(pe.getSourceDescription());
                        chunkImported++;
                        ledger.record(event.toBuilder().state(EventState.IMPORTED).reason(pe.getSourceDescription())
                            .validationStatus(VERIFIED).dedupStatus("NEW").build());
                    }
                } catch (ImportRejectedException e) {
                    enqueueItemForReview(userId, gmailMessageId, itemIndex, from, subject, overallConfidence, pe, e.getMessage());
                    ledger.record(event.toBuilder().state(EventState.REQUIRES_REVIEW).reason(e.getMessage())
                        .validationStatus(VERIFIED).dedupStatus("POSSIBLE_DUPLICATE").build());
                    chunkQueued++;
                    rejected++;
                } catch (Exception e) {
                    log.error("Import failed for {}: {}", pe.getSourceDescription(), e.getMessage(), e);
                    String reason = "Could not be saved automatically (" + e.getMessage() + ") — accept to retry.";
                    enqueueItemForReview(userId, gmailMessageId, itemIndex, from, subject, overallConfidence, pe, reason);
                    ledger.record(event.toBuilder().state(EventState.FAILED_WITH_REASON).reason(reason)
                        .validationStatus(VERIFIED).dedupStatus(NOT_CHECKED).build());
                    chunkQueued++;
                    rejected++;
                }
                itemIndex++;
            }
            imported += chunkImported;
            queuedForReview += chunkQueued;

            audit.record(userId, EXTRACT_TASK, gmailMessageId, PromptLibrary.TRANSACTION_EXTRACTION.system(), prompt, completion,
                overallConfidence, chunkImported > 0 ? "ACCEPTED" : "REVIEW_REQUIRED",
                chunkImported + " imported, " + chunkQueued + " queued for review");
        }

        String unreadKey = "d" + itemIndexBase + ":unread";
        String totalsKey = "d" + itemIndexBase + ":totals";
        if (!unreadParts.isEmpty()) {
            // Some or all of the source could not be read. The email is reported as incomplete so
            // the sync retries it (already-booked lines are recognised and not booked twice), and
            // a placeholder in the review queue makes the gap visible in the meantime.
            String reason = (extractorUnavailable ? "The extractor was unavailable" : "The extractor returned unreadable output")
                + " for " + String.join(", ", unreadParts) + ", so it was left unprocessed — it will be retried on the next sync.";
            enqueueWholeEmailForReview(userId, gmailMessageId, itemIndexBase - 1, from, subject, reason);
            ledger.record(emailLevelEvent(userId, gmailMessageId, unreadKey, itemIndexBase - 1, src, statementProvider,
                lastCompletion, EventState.RECONCILIATION_REQUIRED, reason));
            queuedForReview++;
            return Result.builder()
                .outcome(extractorUnavailable && imported == 0 ? Outcome.UNAVAILABLE : Outcome.REVIEW)
                .imported(imported).duplicates(duplicates).conflicts(conflicts).extracted(extracted)
                .queuedForReview(queuedForReview).rejected(rejected).resolved(resolved)
                .summaries(summaries).incomplete(true).detail(reason).build();
            // (totals are not compared for a partly unread document — lines are missing by definition)
        }
        emailReviewService.clearWholeEmailPlaceholder(userId, gmailMessageId, itemIndexBase - 1);
        ledger.resolveIfOpen(userId, gmailMessageId, unreadKey, "Read in full on a later sync.");

        String totalsCheck = totals.check();
        if (!anyTransactions && totals.statesActivity()) {
            // The statement says money moved but no line was read: a complete miss, not "no
            // transactions". Kept retryable instead of being closed as resolved.
            String detail = "The statement states totals, but no transaction lines could be read from it.";
            ledger.record(emailLevelEvent(userId, gmailMessageId, totalsKey, null, src, statementProvider,
                lastCompletion, EventState.RECONCILIATION_REQUIRED, detail));
            return Result.builder().outcome(Outcome.REVIEW).incomplete(true)
                .totalsCheck("MISMATCHED").totalsDetail(detail).detail(detail).build();
        }
        if ("MISMATCHED".equals(totalsCheck)) {
            // A line the model skipped, or read wrong. What was read is kept; the gap stays open
            // until a person checks the document against what was recorded.
            log.warn("Statement totals do not match the extracted lines for {}: {}", gmailMessageId, totals.detail());
            ledger.record(emailLevelEvent(userId, gmailMessageId, totalsKey, null, src, statementProvider,
                lastCompletion, EventState.RECONCILIATION_REQUIRED, totals.detail()));
        } else {
            ledger.resolveIfOpen(userId, gmailMessageId, totalsKey, "The document's own totals match the lines read from it.");
        }
        if (!anyTransactions) {
            return Result.builder().outcome(Outcome.NOT_FINANCIAL)
                .totalsCheck(totalsCheck).totalsDetail(totals.detail())
                .detail("Model found no transactions").build();
        }

        // Everything found was either booked, already on record, or needs nothing: the email is accounted for.
        Outcome outcome = imported > 0 || ((duplicates + conflicts + resolved) > 0 && queuedForReview == 0) ? Outcome.IMPORTED
            : queuedForReview > 0 ? Outcome.REVIEW : Outcome.NOT_FINANCIAL;
        return Result.builder()
            .outcome(outcome).imported(imported).duplicates(duplicates).conflicts(conflicts).extracted(extracted)
            .queuedForReview(queuedForReview).rejected(rejected).resolved(resolved)
            .totalsCheck(totalsCheck).totalsDetail(totals.detail())
            .summaries(summaries).build();
    }

    private static final String VERIFIED = "VERIFIED";
    private static final String FAILED = "FAILED";
    private static final String NOT_CHECKED = "NOT_CHECKED";

    /** What the model reported, before any of it is trusted: identifies the event within its document. */
    private String rawEventKey(JsonNode t) {
        BigDecimal amount = json.decimal(t, "amount_inr");
        if (amount == null) amount = json.decimal(t, "amount");
        return String.join("|", String.valueOf(up(json.str(t, "instrument_type"))),
            String.valueOf(up(json.str(t, "transaction_type"))), String.valueOf(up(json.str(t, "sub_type"))),
            amount == null ? "" : amount.stripTrailingZeros().toPlainString(),
            String.valueOf(json.str(t, "transaction_date")));
    }

    private EmailFinancialEvent eventFrom(JsonNode t, Long userId, String gmailMessageId, String key, int itemIndex,
                                          SourceDoc src, String statementProvider, LlmCompletion completion, Double confidence) {
        String instrumentType = up(json.str(t, "instrument_type"));
        String transactionType = up(json.str(t, "transaction_type"));
        String subType = up(json.str(t, "sub_type"));
        BigDecimal amountInr = json.decimal(t, "amount_inr");
        String currency = up(json.str(t, "currency"));
        BigDecimal amount = amountInr != null ? amountInr : json.decimal(t, "amount");
        String merchant = firstNonBlank(json.str(t, "merchant"), json.str(t, "scheme_name"), json.str(t, "bank"));
        String instrument = firstNonBlank(json.str(t, "scheme_name"), json.str(t, "symbol"), json.str(t, "isin"));
        return base(userId, gmailMessageId, key, itemIndex, src, statementProvider, completion)
            .confidence(confidence)
            .eventType(String.join("/", java.util.stream.Stream.of(instrumentType, transactionType, subType)
                .filter(java.util.Objects::nonNull).toList()))
            .eventStatus(Optional.ofNullable(up(json.str(t, "status"))).orElse("SUCCESS"))
            .amount(amount)
            .currency(amountInr != null ? "INR" : currency != null ? currency : amount != null ? "INR" : null)
            .eventDate(parseDate(json.str(t, "transaction_date")))
            .merchant(merchant != null ? merchant : "UNKNOWN")
            .account(firstNonBlank(json.str(t, "folio_number"), json.str(t, "payment_method"), json.str(t, "client_id")))
            .instrument(instrument)
            .reference(json.str(t, "trade_number"))
            .evidence(json.str(t, "evidence"))
            .build();
    }

    private EmailFinancialEvent emailLevelEvent(Long userId, String gmailMessageId, String key, Integer itemIndex,
                                                SourceDoc src, String statementProvider, LlmCompletion completion,
                                                EventState state, String reason) {
        return base(userId, gmailMessageId, key, itemIndex, src, statementProvider, completion)
            .sourceKind(EmailFinancialEvent.EMAIL).eventType("DOCUMENT").state(state).reason(reason)
            .validationStatus(NOT_CHECKED).dedupStatus(NOT_CHECKED).build();
    }

    private static EmailFinancialEvent.EmailFinancialEventBuilder base(Long userId, String gmailMessageId, String key,
                                                                        Integer itemIndex, SourceDoc src,
                                                                        String statementProvider, LlmCompletion completion) {
        return EmailFinancialEvent.builder()
            .userId(userId).gmailMessageId(gmailMessageId).eventKey(key).itemIndex(itemIndex)
            .sourceKind(src.isDocument() ? EmailFinancialEvent.ATTACHMENT : EmailFinancialEvent.BODY)
            .attachmentId(src.attachmentId()).attachmentName(src.name()).documentHash(src.documentHash())
            .extractionMethod(src.extractionMethod() != null ? src.extractionMethod() : src.attachmentId() != null
                ? com.marketai.common.ledger.Provenance.PDF_LLM : com.marketai.common.ledger.Provenance.EMAIL_LLM)
            .statementProvider(statementProvider)
            .llmProvider(completion != null ? completion.getProvider() : null)
            .llmModel(completion != null ? completion.getModel() : null)
            .promptVersion(completion != null && completion.getPromptVersion() != null
                ? completion.getPromptVersion() : PromptLibrary.TRANSACTION_EXTRACTION.tag())
            .extractedAt(java.time.LocalDateTime.now());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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

            // Snapshots are append-only history, one per statement date: re-reading the same
            // statement (a resync, a forwarded copy) must not add it again.
            if (casBalanceSnapshotRepository.existsByUserIdAndFolioAndSchemeCodeAndAsOfDate(
                    userId, folio, schemeCode, asOfDate)) {
                continue;
            }
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
     * A bank statement's own stated totals against the lines extracted from it: the check that
     * catches a line the model skipped. Only span-verified totals are used — a total that isn't
     * written in the cited line is ignored, never compared.
     */
    final class StatementTotals {
        /** Rounding across many lines; anything larger is a missing or misread line. */
        private static final BigDecimal TOLERANCE = new BigDecimal("1.00");

        private BigDecimal statedDebits, statedCredits, opening, closing;
        private BigDecimal lineDebits = BigDecimal.ZERO, lineCredits = BigDecimal.ZERO;
        private int bankLines;
        /** The number of transactions (or trades) the document says it lists, and how many were read. */
        private Integer statedCount;
        private int lines;

        void readStated(JsonNode node, String sourceText) {
            if (node == null || !node.isObject()) return;
            String evidence = json.str(node, "evidence");
            if (!SpanVerifier.appearsIn(sourceText, evidence)) return;
            statedDebits = first(statedDebits, grounded(node, "total_debits", evidence));
            statedCredits = first(statedCredits, grounded(node, "total_credits", evidence));
            opening = first(opening, grounded(node, "opening_balance", evidence));
            closing = first(closing, grounded(node, "closing_balance", evidence));
            if (statedCount == null) {
                Integer count = json.integer(node, "transaction_count");
                if (count != null && count >= 0 && SpanVerifier.containsAmount(evidence, BigDecimal.valueOf(count))) {
                    statedCount = count;
                }
            }
        }

        private BigDecimal grounded(JsonNode node, String field, String evidence) {
            BigDecimal v = json.decimal(node, field);
            return v != null && SpanVerifier.containsAmount(evidence, v) ? v : null;
        }

        private static BigDecimal first(BigDecimal current, BigDecimal next) {
            return current != null ? current : next;
        }

        void addLines(JsonNode txns) {
            lines += txns.size();
            for (JsonNode t : txns) {
                String instrument = up(json.str(t, "instrument_type"));
                if (!"BANK".equals(instrument) && !"UPI".equals(instrument)) continue;
                BigDecimal amount = json.decimal(t, "amount_inr");
                if (amount == null) continue;
                bankLines++;
                String type = up(json.str(t, "transaction_type"));
                if ("DEBIT".equals(type)) lineDebits = lineDebits.add(amount.abs());
                else if ("CREDIT".equals(type)) lineCredits = lineCredits.add(amount.abs());
            }
        }

        /** The statement's own figures say money moved in the period. */
        boolean statesActivity() {
            return (statedCount != null && statedCount > 0) || (statedDebits != null && statedDebits.signum() != 0)
                || (statedCredits != null && statedCredits.signum() != 0)
                || (opening != null && closing != null && opening.compareTo(closing) != 0);
        }

        /** null when the statement states no usable totals or has no bank lines. */
        String check() {
            boolean amounts = bankLines > 0
                && (statedDebits != null || statedCredits != null || (opening != null && closing != null));
            if (!amounts && statedCount == null) return null;
            return detail() == null ? "MATCHED" : "MISMATCHED";
        }

        /** What didn't add up, or null when everything stated matched. */
        String detail() {
            List<String> gaps = new ArrayList<>();
            if (statedCount != null && statedCount != lines) {
                gaps.add("count: the document says " + statedCount + " transaction(s), " + lines + " were read");
            }
            if (bankLines > 0) addAmountGaps(gaps);
            return gaps.isEmpty() ? null
                : "The statement's totals don't match the lines read from it (" + String.join("; ", gaps)
                    + ") — a line may have been missed or misread.";
        }

        private void addAmountGaps(List<String> gaps) {
            if (statedDebits != null && off(statedDebits, lineDebits)) {
                gaps.add("debits: statement says ₹" + statedDebits.toPlainString() + ", lines add up to ₹" + lineDebits.toPlainString());
            }
            if (statedCredits != null && off(statedCredits, lineCredits)) {
                gaps.add("credits: statement says ₹" + statedCredits.toPlainString() + ", lines add up to ₹" + lineCredits.toPlainString());
            }
            if (opening != null && closing != null) {
                BigDecimal expected = opening.add(lineCredits).subtract(lineDebits);
                if (off(closing, expected)) {
                    gaps.add("balance: opening ₹" + opening.toPlainString() + " + credits − debits = ₹"
                        + expected.toPlainString() + ", but the closing balance is ₹" + closing.toPlainString());
                }
            }
        }

        private static boolean off(BigDecimal stated, BigDecimal computed) {
            return stated.subtract(computed).abs().compareTo(TOLERANCE) > 0;
        }
    }

    /** Either a bookable transaction or the specific reason it has to go to review instead. */
    record Extraction(ParsedEmail parsed, String reason, boolean resolved) {
        Extraction(ParsedEmail parsed, String reason) { this(parsed, reason, false); }
        static Extraction ok(ParsedEmail pe) { return new Extraction(pe, null); }
        static Extraction review(String reason) { return new Extraction(null, reason); }
        /** Nothing to book, for the stated reason — recorded as resolved, not sent to review. */
        static Extraction noMoneyMoved(String reason) { return new Extraction(null, reason, true); }
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
        if ("EQUITY".equals(instrumentType) && CORPORATE_ACTIONS.contains(transactionType)) {
            return toCorporateAction(t, transactionType, date, evidence, sourceText);
        }
        if (amount == null) {
            String currency = up(json.str(t, "currency"));
            BigDecimal foreign = json.decimal(t, "amount");
            if (currency != null && !"INR".equals(currency) && foreign != null) {
                // Booking the foreign figure as rupees would be wrong by the exchange rate.
                return Extraction.review("A " + currency + " " + foreign.toPlainString() + " transaction with no rupee "
                    + "amount stated — enter the amount in ₹ that was actually charged or credited.");
            }
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
        // The cited line exists; now the figures must actually be in it. A real line quoted
        // with a different amount is exactly the error span-existence alone cannot catch.
        if (!SpanVerifier.containsAmount(evidence, amount)) {
            return Extraction.review("The amount ₹" + amount.toPlainString()
                + " does not appear in the line it was read from, so it was not trusted.");
        }
        // The date must be in the cited line, or at least stated in the document (an alert
        // often gives the date once, apart from the amount line).
        if (!SpanVerifier.containsDate(evidence, date) && !SpanVerifier.containsDate(sourceText, date)) {
            return Extraction.review("The date " + date + " is not stated in the document, so it was not trusted.");
        }

        // Whether the money actually moved. A failed or cancelled payment is recorded as needing
        // nothing — but only when the source line itself says so; the model's word alone sends
        // it to review instead, so a mislabelled real payment is never closed unseen.
        String status = up(json.str(t, "status"));
        if (status != null && !"SUCCESS".equals(status) && !"REFUNDED".equals(status)
                && !"PARTIALLY_REFUNDED".equals(status)) {
            String what = "₹" + amount.toPlainString() + " on " + date;
            switch (status) {
                case "FAILED", "CANCELLED", "DECLINED", "REVERSED" -> {
                    if (NO_MONEY_MOVED.matcher(evidence).find()) {
                        return Extraction.noMoneyMoved("A " + status.toLowerCase(Locale.ROOT) + " payment of " + what
                            + " — no money moved, so nothing was booked.");
                    }
                    return Extraction.review("The extractor marked the payment of " + what + " as "
                        + status.toLowerCase(Locale.ROOT) + ", but the line it quoted does not say so — confirm whether money moved.");
                }
                case "PENDING" -> {
                    return Extraction.review("The payment of " + what + " is still pending — accept it once it has "
                        + "gone through, or reject it if it failed.");
                }
                default -> {
                    return Extraction.review("It is unclear whether the payment of " + what + " went through (status "
                        + status + ").");
                }
            }
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
        String kind = instrumentType.equals("FD") ? "Fixed" : "Recurring";
        if ("MATURITY".equals(transactionType) || "PREMATURE_CLOSURE".equals(transactionType)) {
            // Matched to the open deposit by the importer, which closes it with the amount paid —
            // the principal comes back as cash and only the excess is interest income.
            if (bank == null) {
                return Extraction.review(kind + " deposit payout of ₹" + amount.toPlainString() + " — the bank could not be read, so it cannot be matched to a deposit.");
            }
            return Extraction.ok(ParsedEmail.builder()
                .type(ParsedEmail.Type.DEPOSIT_CLOSE)
                .instrumentKind(instrumentType)
                .bank(bank)
                .principal(json.decimal(t, "principal_inr"))
                .amount(amount)
                .tds(json.decimal(t, "tds_inr"))
                .tradeDate(date)
                .sourceDescription("AI-extracted " + kind.toLowerCase(Locale.ROOT) + " deposit "
                    + ("PREMATURE_CLOSURE".equals(transactionType) ? "premature closure" : "maturity") + ": " + bank + " ₹" + amount)
                .build());
        }
        if ("INTEREST_PAYOUT".equals(transactionType) || "TDS".equals(transactionType)) {
            boolean tdsOnly = "TDS".equals(transactionType);
            return Extraction.ok(ParsedEmail.builder()
                .type(ParsedEmail.Type.DEPOSIT_INTEREST)
                .instrumentKind(instrumentType)
                .bank(bank)
                .amount(tdsOnly ? null : amount)
                .tds(tdsOnly ? amount : json.decimal(t, "tds_inr"))
                .tradeDate(date)
                .sourceDescription("AI-extracted " + (tdsOnly ? "TDS on deposit interest" : "deposit interest")
                    + (bank != null ? ": " + bank : "") + " ₹" + amount)
                .build());
        }
        if (!"OPEN".equals(transactionType)) {
            return Extraction.review(kind + " deposit event" + (bank != null ? " at " + bank : "")
                + " for ₹" + amount.toPlainString() + " could not be identified (" + transactionType + ").");
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

        String tt = transactionType == null ? "" : transactionType;
        if ("IDCW_PAYOUT".equals(tt)) {
            // A distribution paid out to the bank: dividend income from the fund. Units unchanged.
            return ParsedEmail.builder()
                .type(ParsedEmail.Type.DIVIDEND)
                .symbol(schemeName)
                .merchant(schemeName)
                .amount(amount)
                .tds(json.decimal(t, "tds_inr"))
                .tradeDate(date)
                .sourceDescription("AI-extracted IDCW payout: " + schemeName + " ₹" + amount)
                .build();
        }
        ParsedEmail.Type type = switch (tt) {
            case "REDEMPTION", "SWITCH_OUT" -> ParsedEmail.Type.MF_REDEEM;
            case "PURCHASE", "SIP", "DIVIDEND_REINVEST", "IDCW_REINVEST", "SWITCH_IN" -> ParsedEmail.Type.MF_SIP;
            default -> null;
        };
        if (type == null) return null;
        boolean isSwitch = tt.startsWith("SWITCH_");

        return ParsedEmail.builder()
            .type(type)
            // Both legs of a switch carry a marker; the parser gives them one shared group per
            // email, so the redemption is known to have gone into the other fund, not to a bank.
            .linkGroup(isSwitch ? "SWITCH" : null)
            .idcwReinvest("IDCW_REINVEST".equals(tt) || "DIVIDEND_REINVEST".equals(tt))
            .tds(json.decimal(t, "tds_inr"))
            .fundName(schemeName)
            .folio(json.str(t, "folio_number"))
            .isin(json.str(t, "isin"))
            .nav(json.decimal(t, "nav"))
            .units(json.decimal(t, "units"))
            .amount(amount)
            .tradeDate(date)
            .sourceDescription("AI-extracted " + (isSwitch ? tt.replace('_', ' ').toLowerCase(Locale.ROOT) : type)
                + ": " + schemeName + " ₹" + amount)
            .build();
    }

    /** Wording that says a payment did not happen, or was put back. */
    static final java.util.regex.Pattern NO_MONEY_MOVED = java.util.regex.Pattern.compile(
        "(?i)\\b(fail(ed|ure)?|declined|unsuccessful|not (been )?(processed|completed)|cancell?ed|rejected|revers(ed|al)"
            + "|could not be (completed|processed))\\b");

    /** Wording that says a credit gives back an earlier payment. */
    static final java.util.regex.Pattern REFUND_WORDING = java.util.regex.Pattern.compile(
        "(?i)(refund|revers|chargeback|charge back|returned|cancell?ation credit|credited back|money back)");

    static final java.util.Set<String> CORPORATE_ACTIONS = java.util.Set.of("SPLIT", "BONUS", "MERGER", "DEMERGER");

    /**
     * A split, bonus, merger or demerger. No money moves, so there is no amount to verify;
     * instead the ratio must be in the cited line and the date in the document.
     */
    private Extraction toCorporateAction(JsonNode t, String action, LocalDate date, String evidence, String sourceText) {
        String rawSymbol = json.str(t, "symbol");
        String symbol = resolveSymbol(rawSymbol);
        String label = action.charAt(0) + action.substring(1).toLowerCase(Locale.ROOT);
        if (!SpanVerifier.appearsIn(sourceText, evidence)) {
            return Extraction.review("The " + label.toLowerCase(Locale.ROOT) + " line quoted could not be found in the document.");
        }
        if (date == null || (!SpanVerifier.containsDate(evidence, date) && !SpanVerifier.containsDate(sourceText, date))) {
            return Extraction.review(label + (rawSymbol != null ? " of " + rawSymbol : "") + ": the record or allotment date is not stated.");
        }
        if (symbol == null) {
            return Extraction.review(label + " announced, but the stock (" + rawSymbol + ") could not be confirmed.");
        }
        if ("DEMERGER".equals(action)) {
            // The cost of the original shares is divided between the two companies in a ratio the
            // company publishes separately; without it, neither holding's cost can be stated.
            return Extraction.review("Demerger of " + symbol + (json.str(t, "new_symbol") != null ? " into " + json.str(t, "new_symbol") : "")
                + " — record it once the company's cost-apportionment ratio is known, so both holdings carry the right cost.");
        }
        BigDecimal from = json.decimal(t, "ratio_from");
        BigDecimal to = json.decimal(t, "ratio_to");
        Integer credited = json.integer(t, "quantity");
        boolean ratioStated = from != null && to != null && from.signum() > 0 && to.signum() > 0
            && SpanVerifier.containsAmount(evidence, from) && SpanVerifier.containsAmount(evidence, to);
        boolean unitsStated = "BONUS".equals(action) && credited != null && credited > 0
            && SpanVerifier.containsAmount(evidence, BigDecimal.valueOf(credited));
        if (!ratioStated && !unitsStated) {
            return Extraction.review(label + " of " + symbol + ": the ratio could not be read from the cited line.");
        }
        String newSymbol = "MERGER".equals(action) ? resolveSymbol(json.str(t, "new_symbol")) : null;
        if ("MERGER".equals(action) && newSymbol == null) {
            return Extraction.review("Merger of " + symbol + ": the company whose shares replace it could not be confirmed.");
        }
        return Extraction.ok(ParsedEmail.builder()
            .type(ParsedEmail.Type.CORPORATE_ACTION)
            .corporateAction(action)
            .symbol(symbol)
            .newSymbol(newSymbol)
            .ratioFrom(ratioStated ? from : null)
            .ratioTo(ratioStated ? to : null)
            .units(unitsStated ? BigDecimal.valueOf(credited) : null)
            .tradeDate(date)
            .sourceDescription("AI-extracted " + label.toLowerCase(Locale.ROOT) + ": " + symbol
                + (ratioStated ? " " + from.stripTrailingZeros().toPlainString() + ":" + to.stripTrailingZeros().toPlainString() : ""))
            .build());
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

        // Rights shares are bought at the issue price; shares tendered in a buyback are sold to
        // the company. Both move money and units like an ordinary trade.
        ParsedEmail.Type type = "SELL".equals(transactionType) || "BUYBACK".equals(transactionType) ? ParsedEmail.Type.TRADE_SELL
            : "BUY".equals(transactionType) || "RIGHTS".equals(transactionType) ? ParsedEmail.Type.TRADE_BUY : null;
        if (type == null || symbol == null || price == null || quantity == null) return null;
        String tradeNo = json.str(t, "trade_number");

        return ParsedEmail.builder()
            .type(type)
            .symbol(symbol)
            .quantity(quantity)
            .price(price)
            .charges(json.decimal(t, "charges_inr"))
            .tradeReference(tradeNo != null && !tradeNo.isBlank() ? tradeNo.trim() : null)
            .amount(amount)
            .isin(json.str(t, "isin"))
            .dpId(json.str(t, "dp_id"))
            .clientId(json.str(t, "client_id"))
            .tradeDate(date)
            .sourceDescription("AI-extracted " + ("RIGHTS".equals(transactionType) ? "rights allotment"
                : "BUYBACK".equals(transactionType) ? "buyback" : type.toString()) + ": " + symbol + " ₹" + amount)
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
        String subType = up(json.str(t, "sub_type"));
        String utr = json.str(t, "trade_number");
        if (subType != null) {
            Extraction special = bankSubType(subType, transactionType, merchant, paymentMethod, amount, date, evidence, utr);
            if (special != null) return special;
        }
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
     * Bank lines that are not ordinary spending or income. Returns null to fall through to the
     * ordinary debit/credit handling.
     */
    private Extraction bankSubType(String subType, String direction, String merchant, String paymentMethod,
                                   BigDecimal amount, LocalDate date, String evidence, String reference) {
        boolean credit = "CREDIT".equals(direction);
        boolean debit = "DEBIT".equals(direction);
        switch (subType) {
            case "REFUND", "REVERSAL" -> {
                if (!credit) return null;
                // A refund reduces an earlier purchase instead of counting as income, so the
                // label must come from the line itself: a model calling a salary credit a
                // "refund" would otherwise quietly remove it from income.
                if (!REFUND_WORDING.matcher(evidence).find()) {
                    return Extraction.review("The extractor called this ₹" + amount.toPlainString() + " credit a "
                        + subType.toLowerCase(Locale.ROOT) + ", but the line it quoted does not say so — confirm what it is.");
                }
                // Linked by the importer to the purchase it reverses, reducing that spend.
                return Extraction.ok(ParsedEmail.builder()
                    .type(ParsedEmail.Type.REFUND)
                    .merchant(merchant != null ? merchant : SpendCategorizer.extractMerchant(evidence))
                    .paymentMethod(paymentMethod)
                    .amount(amount)
                    .tradeDate(date)
                    .tradeReference(reference)
                    .sourceDescription("AI-extracted " + subType.toLowerCase(Locale.ROOT) + ": ₹" + amount
                        + (merchant != null ? " from " + merchant : ""))
                    .build());
            }
            case "FEE" -> {
                if (!debit) return null;
                return Extraction.ok(ParsedEmail.builder()
                    .type(ParsedEmail.Type.EXPENSE)
                    .category(ExpenseCategory.BANK_CHARGES.getLabel())
                    .merchant(merchant != null ? merchant : paymentMethod)
                    .paymentMethod(paymentMethod)
                    .amount(amount)
                    .tradeDate(date)
                    .sourceDescription("AI-extracted bank/card charge: ₹" + amount)
                    .build());
            }
            case "INTEREST" -> {
                if (!credit) return null;
                return Extraction.ok(ParsedEmail.builder()
                    .type(ParsedEmail.Type.INCOME)
                    .incomeSource("Interest")
                    .merchant(merchant != null ? merchant : paymentMethod)
                    .paymentMethod(paymentMethod)
                    .amount(amount)
                    .tradeDate(date)
                    .sourceDescription("AI-extracted savings interest: ₹" + amount)
                    .build());
            }
            case "EMI" -> {
                if (!debit) return null;
                return Extraction.ok(ParsedEmail.builder()
                    .type(ParsedEmail.Type.EXPENSE)
                    .category(ExpenseCategory.EMI.getLabel())
                    .merchant(merchant)
                    .paymentMethod(paymentMethod)
                    .amount(amount)
                    .tradeDate(date)
                    .sourceDescription("AI-extracted EMI: ₹" + amount + (merchant != null ? " to " + merchant : ""))
                    .build());
            }
            case "EMI_CONVERSION" -> {
                // The purchase was already booked as spend; the EMIs that follow repay it. Booking
                // the conversion, or each EMI as new spend, would count the purchase again.
                return Extraction.review("A purchase of ₹" + amount.toPlainString() + " was converted to EMIs"
                    + (merchant != null ? " (" + merchant + ")" : "") + ". The purchase is already counted as spending; "
                    + "record the loan under Loans if you want its instalments tracked.");
            }
            case "ATM_WITHDRAWAL" -> {
                // Cash leaves the tracked accounts and is spent untracked: counted as spending,
                // as before, so month totals don't silently drop the cash.
                if (!debit) return null;
                return Extraction.ok(ParsedEmail.builder()
                    .type(ParsedEmail.Type.EXPENSE)
                    .category(ExpenseCategory.UNCATEGORIZED.getLabel())
                    .merchant("ATM cash withdrawal")
                    .paymentMethod(paymentMethod)
                    .amount(amount)
                    .tradeDate(date)
                    .sourceDescription("AI-extracted cash withdrawal: ₹" + amount)
                    .build());
            }
            case "OWN_TRANSFER" -> {
                if (!credit && !debit) return null;
                return Extraction.ok(ParsedEmail.builder()
                    .type(ParsedEmail.Type.OWN_TRANSFER)
                    .incoming(credit)
                    .merchant(merchant)
                    .paymentMethod(paymentMethod)
                    .amount(amount)
                    .tradeDate(date)
                    .tradeReference(reference)
                    .sourceDescription("AI-extracted own-account transfer " + (credit ? "in" : "out") + ": ₹" + amount)
                    .build());
            }
            default -> { return null; }
        }
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
