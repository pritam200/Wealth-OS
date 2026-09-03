package com.marketai.gmail.repository;

import com.marketai.gmail.entity.SavedPdfPassword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedPdfPasswordRepository extends JpaRepository<SavedPdfPassword, Long> {
    Optional<SavedPdfPassword> findByUserIdAndProviderKey(Long userId, String providerKey);
    List<SavedPdfPassword> findByUserIdOrderByUpdatedAtDescCreatedAtDesc(Long userId);
    Optional<SavedPdfPassword> findByIdAndUserId(Long id, Long userId);
}
