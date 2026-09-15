package com.marketai.document.repository;

import com.marketai.document.entity.DocumentSource;
import com.marketai.document.entity.DocumentStatus;
import com.marketai.document.entity.FinancialDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FinancialDocumentRepository extends JpaRepository<FinancialDocument, Long> {

    Optional<FinancialDocument> findByUserIdAndSourceAndSourceRef(
        Long userId, DocumentSource source, String sourceRef);

    List<FinancialDocument> findByUserIdAndStatus(Long userId, DocumentStatus status);

    List<FinancialDocument> findByUserIdAndStatusIn(Long userId, List<DocumentStatus> statuses);

    long countByUserIdAndStatus(Long userId, DocumentStatus status);

    /**
     * Documents that should be retried. FAILED is retryable by design — a transient error must
     * not permanently block an import — but the attempt cap stops a genuinely broken document
     * from being reprocessed forever.
     */
    @Query("""
           SELECT d FROM FinancialDocument d
           WHERE d.status = com.marketai.document.entity.DocumentStatus.FAILED
             AND d.attempts < :maxAttempts
           ORDER BY d.statusChangedAt ASC
           """)
    List<FinancialDocument> findRetryable(@Param("maxAttempts") int maxAttempts);

    /**
     * Documents stuck mid-flight — claimed for processing but never resolved, typically because
     * the worker died. Without this they would sit in PROCESSING indefinitely with nothing
     * reporting them.
     */
    @Query("""
           SELECT d FROM FinancialDocument d
           WHERE d.status IN (com.marketai.document.entity.DocumentStatus.PROCESSING,
                              com.marketai.document.entity.DocumentStatus.PARSED)
             AND d.statusChangedAt < :before
           """)
    List<FinancialDocument> findStalled(@Param("before") LocalDateTime before);

    /** Status counts for the Data Health surface. */
    @Query("SELECT d.status, COUNT(d) FROM FinancialDocument d WHERE d.userId = :userId GROUP BY d.status")
    List<Object[]> countByStatusForUser(@Param("userId") Long userId);
}
