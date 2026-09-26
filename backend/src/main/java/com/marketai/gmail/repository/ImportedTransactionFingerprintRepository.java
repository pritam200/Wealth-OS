package com.marketai.gmail.repository;

import com.marketai.gmail.entity.ImportedTransactionFingerprint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportedTransactionFingerprintRepository extends JpaRepository<ImportedTransactionFingerprint, Long> {
    boolean existsByUserIdAndFingerprint(Long userId, String fingerprint);
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
