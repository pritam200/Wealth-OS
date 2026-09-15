package com.marketai.auth.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
@Slf4j
public class JwtService {

    @Value("${app.jwt.secret:}")
    private String secret;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    private static final String ENV_KEY = "JWT_SECRET";

    /**
     * The signing secret used to be a literal committed in application.yml as the dev-profile
     * default. Anyone with the repo could mint a valid token for any account, and that default
     * silently applied to any environment where JWT_SECRET happened to be unset.
     *
     * Now: use the configured value if present, otherwise generate a strong random secret once
     * and persist it to the gitignored gmail.env — the same mechanism PasswordCipher already
     * uses for its local key — so nothing secret lives in version control and sessions still
     * survive a restart.
     */
    @jakarta.annotation.PostConstruct
    void initSecret() {
        if (secret != null && !secret.trim().isEmpty()) return;

        secret = loadFromEnvFile();
        if (secret != null && !secret.trim().isEmpty()) return;

        byte[] random = new byte[64];   // 512-bit, comfortably above HS256's requirement
        new java.security.SecureRandom().nextBytes(random);
        secret = java.util.Base64.getEncoder().encodeToString(random);
        persistToEnvFile(secret);
        log.warn("No JWT_SECRET configured — generated one and saved it to gmail.env (gitignored). "
               + "Set JWT_SECRET explicitly for any deployed environment.");
    }

    private String loadFromEnvFile() {
        java.io.File envFile = new java.io.File("gmail.env");
        if (!envFile.exists()) envFile = new java.io.File("backend/gmail.env");
        if (!envFile.exists()) return null;
        try {
            for (String line : java.nio.file.Files.readAllLines(envFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(ENV_KEY + "=")) {
                    return trimmed.substring(ENV_KEY.length() + 1).trim();
                }
            }
        } catch (Exception e) {
            log.debug("Could not read gmail.env: {}", e.getMessage());
        }
        return null;
    }

    private void persistToEnvFile(String value) {
        java.io.File envFile = new java.io.File("gmail.env");
        if (!envFile.exists() && new java.io.File("backend").isDirectory()) {
            envFile = new java.io.File("backend/gmail.env");
        }
        try {
            java.util.List<String> lines = envFile.exists()
                ? java.nio.file.Files.readAllLines(envFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)
                : new java.util.ArrayList<String>();
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith(ENV_KEY + "=")) {
                    lines.set(i, ENV_KEY + "=" + value);
                    found = true;
                    break;
                }
            }
            if (!found) lines.add(ENV_KEY + "=" + value);
            java.nio.file.Files.write(envFile.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Not fatal: the in-memory secret still works for this run; only persistence failed.
            log.warn("Could not persist {} to {}: {} — sessions will not survive a restart.",
                ENV_KEY, envFile.getPath(), e.getMessage());
        }
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(
                java.util.Base64.getEncoder().encodeToString(secret.getBytes()));
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
