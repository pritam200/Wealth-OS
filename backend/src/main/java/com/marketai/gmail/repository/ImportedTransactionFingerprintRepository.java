package com.marketai.gmail.repository;

import com.marketai.gmail.entity.ImportedTransactionFingerprint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportedTransactionFingerprintRepository extends JpaRepository<ImportedTransactionFingerprint, Long> {
    boolean existsByUserIdAndFingerprint(Long userId, String fingerprint);

    /** Lines booked from this email before {@code before} by a read other than {@code version}
     *  (or by one that predates versions). */
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(f) > 0 FROM ImportedTransactionFingerprint f "
        + "WHERE f.userId = :userId AND f.gmailMessageId = :msgId AND f.importedAt < :before "
        + "AND f.extractionMethod IS NOT NULL AND (f.extractionVersion IS NULL OR f.extractionVersion <> :version)")
    boolean existsReadByOtherVersion(@org.springframework.data.repository.query.Param("userId") Long userId,
                                     @org.springframework.data.repository.query.Param("msgId") String gmailMessageId,
                                     @org.springframework.data.repository.query.Param("version") String version,
                                     @org.springframework.data.repository.query.Param("before") java.time.LocalDateTime before);
    java.util.Optional<ImportedTransactionFingerprint> findFirstByUserIdAndFingerprint(Long userId, String fingerprint);
    void deleteByUserId(Long userId);

    /**
     * Tier-1 identity lookup. Scoped to the user so one person's UTR can never match another's,
     * and typed so a cheque number can never collide with a UTR that happens to share digits.
     */
    java.util.Optional<ImportedTransactionFingerprint>
        findFirstByUserIdAndExternalRefAndExternalRefType(Long userId, String externalRef, String externalRefType);

    /** Conflicting restatements awaiting a human decision. */
    java.util.List<ImportedTransactionFingerprint> findByUserIdAndConflictDetectedTrue(Long userId);

    /**
     * Candidate pool for {@code TransactionMatchScorer} — narrowed by exact amount (a real bank
     * amount is exact, so unlike date/merchant it is safe to filter on rather than merely score),
     * leaving date/merchant/card/reference to be scored in memory over a small set.
     */
    java.util.List<ImportedTransactionFingerprint> findByUserIdAndAmount(Long userId, java.math.BigDecimal amount);

    /** Rows awaiting human review, oldest first. */
    java.util.List<ImportedTransactionFingerprint> findByUserIdAndDuplicateStateOrderByImportedAtAsc(Long userId, String duplicateState);
}
