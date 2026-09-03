package com.marketai.gmail.repository;

import com.marketai.gmail.entity.GmailToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface GmailTokenRepository extends JpaRepository<GmailToken, Long> {
    Optional<GmailToken> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
    List<GmailToken> findAll();
}
