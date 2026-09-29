package com.marketai.gmail.ledger;

import com.marketai.gmail.entity.PendingPdf;
import com.marketai.gmail.entity.ProcessedEmail;
import com.marketai.gmail.repository.PendingPdfRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The import manifest of one email: its body and every attachment together, with each event
 * found and where it ended up. Built from the financial-event ledger and the attachment queue,
 * and saved on the email's {@link ProcessedEmail} row so lists can show it without recomputing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailManifestService {

    public static final String COMPLETE = "COMPLETE";
    public static final String RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";
    public static final String NO_FINANCIAL_EVENTS = "NO_FINANCIAL_EVENTS";
    public static final String FAILED = "FAILED";

    /** Attachment states that are finished: read, recognised as a copy, or dismissed by the user. */
    private static final Set<String> PDF_DONE = Set.of("IMPORTED", "DUPLICATE_DOCUMENT", "DISMISSED");

    private final FinancialEventLedger ledger;
    private final ProcessedEmailRepository processedRepo;
    private final PendingPdfRepository pendingPdfRepo;

    @Value @Builder
    public static class Manifest {
        String gmailMessageId;
        String subject;
        String sender;
        String emailStatus;
        String status;
        Integer attachmentsFound;
        Integer attachmentsProcessed;
        String attachmentNotes;
        int documentsDetected;
        int eventsDetected;
        int eventsImported;
        int eventsDuplicate;
        int eventsResolved;
        int eventsConflict;
        int eventsUnresolved;
        List<String> openAttachments;
        List<EmailFinancialEvent> events;
    }

    /**
     * Recomputes the manifest and saves it on the email's row (when there is one).
     *
     * @param attachmentsFound     null keeps what was recorded at sync time
     * @param attachmentsProcessed null keeps what was recorded at sync time
     * @param attachmentNotes      null keeps what was recorded at sync time
     */
    public Manifest refresh(Long userId, String gmailMessageId, Integer attachmentsFound,
                            Integer attachmentsProcessed, String attachmentNotes) {
        ProcessedEmail row = processedRepo.findByUserIdAndGmailMessageId(userId, gmailMessageId).orElse(null);
        List<EmailFinancialEvent> events = ledger.forEmail(userId, gmailMessageId);
        List<PendingPdf> pdfs = pendingPdfRepo.findByUserIdAndGmailMessageId(userId, gmailMessageId);

        int detected = 0, imported = 0, duplicate = 0, resolved = 0, conflict = 0, unresolved = 0;
        Set<String> documents = new LinkedHashSet<>();
        for (EmailFinancialEvent e : events) {
            boolean emailLevel = EmailFinancialEvent.EMAIL.equals(e.getSourceKind());
            if (!emailLevel) {
                detected++;
                documents.add(EmailFinancialEvent.BODY.equals(e.getSourceKind()) ? "body"
                    : "att:" + (e.getAttachmentName() != null ? e.getAttachmentName() : e.getDocumentHash()));
            }
            if ("CONFLICT".equals(e.getDedupStatus())) conflict++;
            switch (e.getState()) {
                case IMPORTED -> { if (!emailLevel) imported++; }
                case DUPLICATE_OF_EXISTING -> duplicate++;
                case RESOLVED -> { if (!emailLevel) resolved++; }
                default -> unresolved++;
            }
        }
        List<String> open = new ArrayList<>();
        for (PendingPdf p : pdfs) {
            documents.add("att:" + p.getFilename());
            if (!PDF_DONE.contains(p.getStatus())) open.add(p.getFilename() + ": " + describe(p.getStatus()));
        }

        String emailStatus = row != null ? row.getStatus() : null;
        String status = "FAILED".equals(emailStatus) ? FAILED
            : unresolved > 0 || !open.isEmpty() ? RECONCILIATION_REQUIRED
            : detected == 0 ? NO_FINANCIAL_EVENTS
            : COMPLETE;

        if (row != null) {
            if (attachmentsFound != null) row.setAttachmentsFound(attachmentsFound);
            if (attachmentsProcessed != null) row.setAttachmentsProcessed(attachmentsProcessed);
            if (attachmentNotes != null) row.setAttachmentNotes(attachmentNotes.isEmpty() ? null
                : attachmentNotes.length() > 1000 ? attachmentNotes.substring(0, 1000) : attachmentNotes);
            row.setDocumentsDetected(documents.size());
            row.setEventsDetected(detected);
            row.setEventsUnresolved(unresolved + open.size());
            row.setManifestStatus(status);
            try {
                processedRepo.save(row);
            } catch (Exception e) {
                log.warn("Could not save the import manifest for message {}: {}", gmailMessageId, e.getMessage());
            }
        }
        return Manifest.builder()
            .gmailMessageId(gmailMessageId)
            .subject(row != null ? row.getSubject() : pdfs.isEmpty() ? null : pdfs.get(0).getSubject())
            .sender(row != null ? row.getSender() : pdfs.isEmpty() ? null : pdfs.get(0).getSender())
            .emailStatus(emailStatus).status(status)
            .attachmentsFound(row != null ? row.getAttachmentsFound() : attachmentsFound)
            .attachmentsProcessed(row != null ? row.getAttachmentsProcessed() : attachmentsProcessed)
            .attachmentNotes(row != null ? row.getAttachmentNotes() : attachmentNotes)
            .documentsDetected(documents.size())
            .eventsDetected(detected).eventsImported(imported).eventsDuplicate(duplicate)
            .eventsResolved(resolved).eventsConflict(conflict).eventsUnresolved(unresolved + open.size())
            .openAttachments(open).events(events)
            .build();
    }

    public Manifest get(Long userId, String gmailMessageId) {
        return refresh(userId, gmailMessageId, null, null, null);
    }

    /** One unresolved event, with the email it came from. */
    public record UnresolvedEvent(EmailFinancialEvent event, String subject, String sender) {}

    public record UnresolvedView(java.util.Map<EventState, Long> byState, long total, long unresolved,
                                 long emailsWithUnresolved, List<UnresolvedEvent> events) {}

    /** Every event not yet accounted for, most recent first, with totals across the whole ledger. */
    public UnresolvedView unresolved(Long userId, int limit) {
        FinancialEventLedger.Coverage coverage = ledger.coverage(userId);
        java.util.Map<String, ProcessedEmail> emails = new java.util.HashMap<>();
        List<UnresolvedEvent> out = new ArrayList<>();
        for (EmailFinancialEvent e : ledger.unresolved(userId, limit)) {
            ProcessedEmail pe = emails.computeIfAbsent(e.getGmailMessageId(),
                id -> processedRepo.findByUserIdAndGmailMessageId(userId, id).orElse(null));
            out.add(new UnresolvedEvent(e, pe != null ? pe.getSubject() : null, pe != null ? pe.getSender() : null));
        }
        return new UnresolvedView(coverage.byState(), coverage.total(), coverage.unresolved(),
            coverage.emailsWithUnresolved(), out);
    }

    private static String describe(String pdfStatus) {
        return switch (pdfStatus == null ? "" : pdfStatus) {
            case "NEEDS_PASSWORD" -> "waiting for its password";
            case "PASSWORD_FAILED" -> "the saved password did not open it";
            case "NEEDS_OCR" -> "a scanned document that could not be read";
            case "FAILED" -> "could not be read — it will be retried";
            default -> pdfStatus;
        };
    }
}
