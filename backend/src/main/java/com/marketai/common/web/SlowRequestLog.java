package com.marketai.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes every API request slower than {@link #THRESHOLD_MS} to {@code logs/slow-requests.log}:
 * how long it took, how many SQL statements it sent, and how many requests were running at
 * the time. The path only, never the query string, which can carry amounts.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlowRequestLog extends OncePerRequestFilter {

    static final long THRESHOLD_MS = 500;
    private static final Path FILE = Path.of(System.getProperty("user.dir"), "logs", "slow-requests.log");

    private final AtomicInteger inFlight = new AtomicInteger();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        int running = inFlight.incrementAndGet();
        QueryCounter.reset();
        try {
            chain.doFilter(request, response);
        } finally {
            inFlight.decrementAndGet();
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= THRESHOLD_MS) {
                write(String.format("%s %s %s status=%d ms=%d sql=%d running=%d%n", LocalDateTime.now().withNano(0),
                    request.getMethod(), request.getRequestURI(), response.getStatus(), ms, QueryCounter.count(), running));
            }
        }
    }

    private static synchronized void write(String line) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Could not write the slow request log: {}", e.getMessage());
        }
    }
}
