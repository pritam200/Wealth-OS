package com.marketai.provenance;

import com.marketai.common.ledger.Provenance;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.gmail.entity.ImportedTransactionFingerprint;
import com.marketai.gmail.entity.ProcessedEmail;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.rent.repository.RentRepository;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.repository.RecurringDepositRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * "View source" for any ledger record: which email (and attachment) it was read from, how it
 * was extracted and with what confidence, and what the import log recorded about it.
 */
@Service
@RequiredArgsConstructor
public class ProvenanceService {

    private final ExpenseRepository expenseRepo;
    private final IncomeRepository incomeRepo;
    private final TransactionRepository transactionRepo;
    private final FixedDepositRepository fdRepo;
    private final RecurringDepositRepository rdRepo;
    private final RentRepository rentRepo;
    private final ImportedTransactionFingerprintRepository fingerprintRepo;
    private final ProcessedEmailRepository processedEmailRepo;

    @Value @Builder
    public static class SourceView {
        String kind;
        Long id;
        /** MANUAL, EMAIL_LLM, PDF_LLM — or UNKNOWN for records written before provenance existed. */
        String origin;
        Double confidence;
        String gmailMessageId;
        /** Opens the email in Gmail (for the signed-in account). */
        String gmailLink;
        String emailSender;
        LocalDateTime emailProcessedAt;
        String attachmentId;
        String documentHash;
        /** How the import log described the event. */
        String extractedAs;
        /** NEW, NEEDS_REVIEW or MATCHED_TO_EXISTING. */
        String duplicateState;
        String conflictDetail;
        LocalDateTime importedAt;
        /** Provider, model and prompt version that read it, e.g. "ollama:qwen2.5:7b/transaction-extraction-v1". */
        String extractionVersion;
    }

    @Transactional(readOnly = true)
    public SourceView sourceOf(Long userId, String kind, Long id) {
        String emailId = null;
        String fingerprint = null;
        Provenance p = null;      // trades and deposits carry an embedded Provenance
        switch (kind) {
            case "expense" -> {
                var e = expenseRepo.findByIdAndUserId(id, userId).orElseThrow(() -> notFound(kind));
                emailId = e.getSourceEmailId();
                fingerprint = e.getSourceFingerprint();
            }
            case "income" -> {
                var i = incomeRepo.findByIdAndUserId(id, userId).orElseThrow(() -> notFound(kind));
                emailId = i.getSourceEmailId();
                fingerprint = i.getSourceFingerprint();
            }
            case "rent" -> emailId = rentRepo.findByIdAndUserId(id, userId).orElseThrow(() -> notFound(kind)).getSourceEmailId();
            case "transaction" -> p = transactionRepo.findByIdAndHolding_Portfolio_User_Id(id, userId)
                .orElseThrow(() -> notFound(kind)).getProvenance();
            case "fd" -> p = fdRepo.findByIdAndUserId(id, userId).orElseThrow(() -> notFound(kind)).getProvenance();
            case "rd" -> p = rdRepo.findByIdAndUserId(id, userId).orElseThrow(() -> notFound(kind)).getProvenance();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown record type: " + kind);
        }
        boolean embedded = kind.equals("transaction") || kind.equals("fd") || kind.equals("rd");
        if (p != null) {
            emailId = p.getSourceEmailId();
            fingerprint = p.getSourceFingerprint();
        }

        ImportedTransactionFingerprint logged = fingerprint == null ? null
            : fingerprintRepo.findFirstByUserIdAndFingerprint(userId, fingerprint).orElse(null);
        ProcessedEmail email = emailId == null ? null
            : processedEmailRepo.findByUserIdAndGmailMessageId(userId, emailId).orElse(null);

        String origin = p != null && p.getExtractionMethod() != null ? p.getExtractionMethod()
            : logged != null && logged.getExtractionMethod() != null ? logged.getExtractionMethod()
            : emailId != null ? Provenance.EMAIL_LLM
            : embedded && p == null ? "UNKNOWN" : Provenance.MANUAL;
        Double confidence = p != null && p.getExtractionConfidence() != null ? p.getExtractionConfidence()
            : logged != null ? logged.getExtractionConfidence() : null;

        return SourceView.builder()
            .kind(kind).id(id).origin(origin).confidence(confidence)
            .gmailMessageId(emailId)
            .gmailLink(emailId != null ? "https://mail.google.com/mail/u/0/#all/" + emailId : null)
            .emailSender(email != null ? email.getSender() : null)
            .emailProcessedAt(email != null ? email.getProcessedAt() : null)
            .attachmentId(logged != null ? logged.getAttachmentId() : null)
            .documentHash(logged != null ? logged.getDocumentHash() : null)
            .extractedAs(logged != null ? logged.getDescription() : null)
            .duplicateState(logged != null ? logged.getDuplicateState() : null)
            .conflictDetail(logged != null ? logged.getConflictDetail() : null)
            .importedAt(logged != null ? logged.getImportedAt() : null)
            .extractionVersion(logged != null ? logged.getExtractionVersion() : null)
            .build();
    }

    private static ResponseStatusException notFound(String kind) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such " + kind);
    }
}
