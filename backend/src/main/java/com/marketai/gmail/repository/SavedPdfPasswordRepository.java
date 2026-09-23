package com.marketai.gmail.repository;

import com.marketai.gmail.entity.SavedPdfPassword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedPdfPasswordRepository extends JpaRepository<SavedPdfPassword, Long> {
    // Bare-domain lookup — still used where document type genuinely doesn't matter (the
    // cross-provider PAN-sharing search iterates every saved password for the user anyway).
    Optional<SavedPdfPassword> findByUserIdAndProviderKey(Long userId, String providerKey);

    // The type-aware lookup: a saved password only auto-applies to a PDF of the same kind it
    // was learned from. Spring Data translates a null documentType into "IS NULL", so
    // unclassified PDFs still match each other consistently.
    Optional<SavedPdfPassword> findByUserIdAndProviderKeyAndDocumentType(Long userId, String providerKey, String documentType);

    List<SavedPdfPassword> findByUserIdOrderByUpdatedAtDescCreatedAtDesc(Long userId);
    Optional<SavedPdfPassword> findByIdAndUserId(Long id, Long userId);
}
