package com.marketai.ai.review.service;

import com.marketai.ai.intel.EmailIntelResult;
import com.marketai.ai.intel.EmailIntelType;
import com.marketai.ai.review.dto.ReviewDecisionRequest;
import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.auth.entity.User;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.service.ParsedEmailImporter;
import com.marketai.expense.entity.ExpenseCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The human-review queue for uncertain email extractions.
 *
 * Nothing here writes to a financial table directly — an accepted item is handed to
 * {@link ParsedEmailImporter}, so it passes the same SHA-256 fingerprint gate as any other
 * import and cannot double-book a transaction that already arrived another way.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailReviewService {

    private final EmailReviewItemRepository repo;
    private final ParsedEmailImporter importer;

    /**
     * Item index for a "this whole email could not be read" placeholder. Kept apart from the
     * per-transaction indexes (0, 1, …): sharing index 0 meant the first real transaction later
     * extracted from the same email overwrote — or, once judged, was blocked by — the placeholder.
     */
    public static final int WHOLE_EMAIL_ITEM_INDEX = -1;

    /** Queues a whole-email placeholder (the extractor could not read it). Idempotent per gmail
     *  message — a re-sync updates the existing pending row instead of adding another, and never
     *  resurrects a resolved one. */
    @Transactional
    public void enqueue(Long userId, String gmailMessageId, String sender, String subject,
                        EmailIntelResult result) {
        enqueue(userId, gmailMessageId, WHOLE_EMAIL_ITEM_INDEX, sender, subject, result);
    }

    /** Drops a still-pending whole-email placeholder once a later sync has read the email in
     *  full, so the queue does not keep asking about an email that has since been processed. */
    @Transactional
    public void clearWholeEmailPlaceholder(Long userId, String gmailMessageId, int placeholderIndex) {
        if (gmailMessageId == null) return;
        repo.findByUserIdAndGmailMessageIdAndItemIndex(userId, gmailMessageId, placeholderIndex)
            .filter(i -> i.getStatus() == ReviewStatus.PENDING)
            .ifPresent(repo::delete);
    }

    /**
     * Queues an uncertain extraction. Idempotent per (email, item index) — a re-sync updates the
     * existing pending row instead of adding another, and never resurrects a resolved one.
     *
     * <p>{@code itemIndex} exists because one email can yield several extracted items — a
     * 5-trade contract note held back by the sender-trust check, for example. Keying only on
     * the gmail message id meant every item after the first silently overwrote the one before
     * it, so only the last trade in the email ever reached the queue.
     */
    @Transactional
    public void enqueue(Long userId, String gmailMessageId, int itemIndex, String sender, String subject,
                        EmailIntelResult result) {
        EmailReviewItem existing = gmailMessageId == null ? null
            : repo.findByUserIdAndGmailMessageIdAndItemIndex(userId, gmailMessageId, itemIndex).orElse(null);

        if (existing != null && existing.getStatus() != ReviewStatus.PENDING) {
            // Already judged by the user — re-queueing would ask the same question twice.
            return;
        }

        EmailReviewItem item = existing != null ? existing : EmailReviewItem.builder()
            .userId(userId).gmailMessageId(gmailMessageId).itemIndex(itemIndex).build();

        item.setSender(trim(sender, 320));
        item.setSubject(trim(subject, 500));
        item.setProposedType(result.getType() != null ? result.getType().name() : EmailIntelType.UNKNOWN.name());
        item.setConfidence(result.getConfidence() != null ? BigDecimal.valueOf(result.getConfidence()) : null);
        item.setReviewReason(trim(result.getReviewReason(), 500));
        item.setReasoning(result.getReasoning());
        item.setEvidence(result.getEvidence());
        item.setExtractedFields(result.getExtractedFieldsJson());
        item.setStatus(ReviewStatus.PENDING);

        // Headline figures for the list view, read from the already-validated parsed payload
        // when present (never re-derived from the raw model text).
        if (result.getParsed() != null) {
            item.setAmount(result.getParsed().getAmount());
            item.setTransactionDate(result.getParsed().getTradeDate());
            item.setCounterparty(trim(result.getParsed().getMerchant(), 200));
        }
        repo.save(item);
    }

    public List<EmailReviewItem> listPending(Long userId) {
        return repo.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ReviewStatus.PENDING);
    }

    public List<EmailReviewItem> listAll(Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public long pendingCount(Long userId) {
        return repo.countByUserIdAndStatus(userId, ReviewStatus.PENDING);
    }

    /**
     * Whether every item queued from this email has reached a final decision (ACCEPTED, EDITED
     * or REJECTED) — false if none were ever queued, or if any are still PENDING.
     *
     * <p>Used by {@code GmailSyncService}'s retry guard: {@code ProcessedEmail.status} is set to
     * {@code SKIPPED} the moment an item is queued here and is never revisited afterward, so
     * without this check a fully-judged email (accepted, edited, or rejected by a human) would
     * be re-fetched and re-run through the whole pipeline on every single sync, forever — safe
     * (this service's own idempotency guards prevent double-booking), but pure wasted work.
     */
    public boolean isFullyResolved(Long userId, String gmailMessageId) {
        return repo.existsByUserIdAndGmailMessageId(userId, gmailMessageId)
            && !repo.existsByUserIdAndGmailMessageIdAndStatus(userId, gmailMessageId, ReviewStatus.PENDING);
    }

    @Transactional
    public EmailReviewItem decide(User user, Long id, ReviewDecisionRequest req) {
        EmailReviewItem item = repo.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review item not found"));
        if (item.getStatus() != ReviewStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This item has already been resolved");
        }

        String decision = req.getDecision() == null ? "" : req.getDecision().trim().toUpperCase();
        switch (decision) {
            case "REJECT":
                item.setStatus(ReviewStatus.REJECTED);
                break;
            case "ACCEPT":
                importDecided(user, item, item.getProposedType(), item.getAmount(),
                    item.getTransactionDate(), item.getCounterparty(), req.getCorrectedCategory());
                item.setStatus(ReviewStatus.ACCEPTED);
                break;
            case "EDIT":
                String type = req.getCorrectedType() != null ? req.getCorrectedType() : item.getProposedType();
                BigDecimal amount = req.getCorrectedAmount() != null ? req.getCorrectedAmount() : item.getAmount();
                LocalDate date = req.getCorrectedDate() != null ? req.getCorrectedDate() : item.getTransactionDate();
                String party = req.getCorrectedCounterparty() != null ? req.getCorrectedCounterparty() : item.getCounterparty();
                importDecided(user, item, type, amount, date, party, req.getCorrectedCategory());
                item.setProposedType(type);
                item.setAmount(amount);
                item.setTransactionDate(date);
                item.setCounterparty(trim(party, 200));
                item.setStatus(ReviewStatus.EDITED);
                break;
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be ACCEPT, EDIT or REJECT");
        }

        item.setResolvedAt(LocalDateTime.now());
        item.setResolutionNote(trim(req.getNote(), 500));
        return repo.save(item);
    }

    /**
     * Books a reviewed item through the normal importer. A classification with no bookable
     * target (transfer, MF switch, statement-only) is accepted as a *judgement* without
     * creating a financial record — confirming "this was a transfer" must not add income or
     * spending, which is the whole reason those types aren't auto-imported.
     */
    private void importDecided(User user, EmailReviewItem item, String typeName, BigDecimal amount,
                               LocalDate date, String counterparty, String category) {
        EmailIntelType type = EmailIntelType.fromLabel(typeName);
        if (!type.isImportable()) {
            log.info("Review item {} accepted as {} — recorded as a judgement, no financial row created",
                item.getId(), type);
            return;
        }
        if (amount == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "An amount is required to import this item — edit it and supply one.");
        }
        if (date == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A transaction date is required to import this item — edit it and supply one.");
        }

        ParsedEmail pe = ParsedEmail.builder()
            .type(type.importAs())
            .amount(amount)
            .tradeDate(date)
            .merchant(counterparty)
            .category(category != null ? ExpenseCategory.fromLabel(category).getLabel() : null)
            .incomeSource(type == EmailIntelType.SALARY ? "Salary"
                        : type == EmailIntelType.INTEREST_CREDIT ? "Interest"
                        : type == EmailIntelType.RENTAL_INCOME ? "Rental" : null)
            .sourceDescription("Human-reviewed: " + type)
            .userConfirmed(true)
            .build();

        try {
            // Goes through the fingerprint gate like any other import, so accepting a review
            // item that already arrived via a parser cannot create a second record.
            importer.importParsedEmail(user.getId(), user, pe, item.getGmailMessageId());
        } catch (com.marketai.gmail.service.ImportRejectedException e) {
            // The item still can't be booked as-is (e.g. no saved card for a bill yet). A 409
            // with the reason tells the person what to fix; it is not a server fault.
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not import this item: " + e.getMessage());
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
