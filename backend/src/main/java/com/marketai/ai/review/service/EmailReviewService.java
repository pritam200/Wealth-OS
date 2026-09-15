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

    /** Queues an uncertain extraction. Idempotent per gmail message — a re-sync updates the
     *  existing pending row instead of adding another, and never resurrects a resolved one. */
    @Transactional
    public void enqueue(Long userId, String gmailMessageId, String sender, String subject,
                        EmailIntelResult result) {
        EmailReviewItem existing = gmailMessageId == null ? null
            : repo.findByUserIdAndGmailMessageId(userId, gmailMessageId).orElse(null);

        if (existing != null && existing.getStatus() != ReviewStatus.PENDING) {
            // Already judged by the user — re-queueing would ask the same question twice.
            return;
        }

        EmailReviewItem item = existing != null ? existing : EmailReviewItem.builder()
            .userId(userId).gmailMessageId(gmailMessageId).build();

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

        ParsedEmail pe = ParsedEmail.builder()
            .type(type.importAs())
            .amount(amount)
            .tradeDate(date != null ? date : LocalDate.now())
            .merchant(counterparty)
            .category(category != null ? ExpenseCategory.fromLabel(category).getLabel() : null)
            .incomeSource(type == EmailIntelType.SALARY ? "Salary"
                        : type == EmailIntelType.INTEREST_CREDIT ? "Interest"
                        : type == EmailIntelType.RENTAL_INCOME ? "Rental" : null)
            .sourceDescription("Human-reviewed: " + type)
            .build();

        try {
            // Goes through the fingerprint gate like any other import, so accepting a review
            // item that already arrived via a parser cannot create a second record.
            importer.importParsedEmail(user.getId(), user, pe, item.getGmailMessageId());
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
