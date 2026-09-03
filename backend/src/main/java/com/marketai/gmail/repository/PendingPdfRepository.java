package com.marketai.gmail.repository;

import com.marketai.gmail.entity.PendingPdf;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PendingPdfRepository extends JpaRepository<PendingPdf, Long> {
    List<PendingPdf> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
    List<PendingPdf> findByUserIdAndStatusInOrderByCreatedAtDesc(Long userId, List<String> statuses);
    boolean existsByUserIdAndGmailMessageIdAndAttachmentId(Long userId, String gmailMessageId, String attachmentId);
    Optional<PendingPdf> findByUserIdAndGmailMessageIdAndAttachmentId(Long userId, String gmailMessageId, String attachmentId);
    // Gmail's attachmentId is not guaranteed stable across separate messages.get() calls for
    // the same physical attachment, so the attachmentId-keyed lookup above silently fails to
    // catch re-syncs and spawns a fresh PendingPdf row every time. filename is stable per
    // email and is the dedup key that actually works across repeated syncs.
    Optional<PendingPdf> findByUserIdAndGmailMessageIdAndFilename(Long userId, String gmailMessageId, String filename);
    Optional<PendingPdf> findByIdAndUserId(Long id, Long userId);
    // Reuse a known password-format hint across statements from the same institution —
    // once one email from a provider reveals the format, every other statement from that
    // same sender domain gets it for free instead of re-guessing per email.
    Optional<PendingPdf> findFirstByUserIdAndProviderKeyAndPasswordHintIsNotNull(Long userId, String providerKey);
    List<PendingPdf> findByUserIdOrderByCreatedAtDesc(Long userId);

    // Used by the reconciliation report to count PDFs per status without loading every row.
    long countByUserIdAndStatus(Long userId, String status);
}
