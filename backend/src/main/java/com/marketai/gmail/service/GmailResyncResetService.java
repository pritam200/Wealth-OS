package com.marketai.gmail.service;

import com.marketai.gmail.repository.GmailTokenRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The bookkeeping a re-sync clears before it runs. Called by {@link GmailSyncService} only once
 * it holds the user's sync lock, so a re-sync that finds another sync running changes nothing.
 *
 * <p>Transaction fingerprints are never cleared. They are what recognises an already-booked
 * transaction when its email is read again; a full re-sync used to delete them, leaving only the
 * weaker content checks between a re-read mailbox and a second copy of every transaction.
 * The consequence is deliberate: a record the user deleted stays deleted.
 */
@Service
@RequiredArgsConstructor
public class GmailResyncResetService {

    private final ProcessedEmailRepository processedRepo;
    private final GmailTokenRepository tokenRepo;
    private final PdfImportService pdfImportService;

    /** Every email is read again; anything already booked is recognised by its fingerprint. */
    @Transactional
    public void resetForFullResync(Long userId) {
        processedRepo.deleteByUserId(userId);
        tokenRepo.findByUserId(userId).ifPresent(token -> {
            token.setImportedCount(0);
            tokenRepo.save(token);
        });
        pdfImportService.resetFailedPasswords(userId);
        pdfImportService.resetFailedPdfs(userId);
    }

    /** Only emails that were skipped or failed are read again. */
    @Transactional
    public void resetForRetry(Long userId) {
        processedRepo.deleteByUserIdAndStatusIn(userId, List.of("SKIPPED", "FAILED"));
        pdfImportService.resetFailedPasswords(userId);
        pdfImportService.resetFailedPdfs(userId);
    }
}
