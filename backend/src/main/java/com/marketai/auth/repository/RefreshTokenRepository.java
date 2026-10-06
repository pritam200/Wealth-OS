package com.marketai.auth.repository;

import com.marketai.auth.entity.RefreshToken;
import com.marketai.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user = :user")
    void revokeAllUserTokens(User user);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.revoked = true OR rt.expiresAt < CURRENT_TIMESTAMP")
    void deleteExpiredTokens();

    /** (email, number of live refresh tokens) for every account that currently has any. */
    @Query("SELECT rt.user.email, COUNT(rt) FROM RefreshToken rt WHERE rt.revoked = false AND rt.expiresAt > CURRENT_TIMESTAMP GROUP BY rt.user.email ORDER BY COUNT(rt) DESC")
    java.util.List<Object[]> activeSessionCounts();
}
