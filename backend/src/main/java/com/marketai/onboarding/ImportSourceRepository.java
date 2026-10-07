package com.marketai.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImportSourceRepository extends JpaRepository<ImportSource, Long> {
    List<ImportSource> findByUserIdOrderByKindAscNameAsc(Long userId);
    Optional<ImportSource> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserIdAndKindAndNameIgnoreCase(Long userId, SourceKind kind, String name);
}
