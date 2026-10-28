package com.marketai.common.cache;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A counter that moves whenever a request may have changed data — any API call other than a
 * read that succeeded. A cached page result taken at an older version is known to be out of
 * date and is recomputed, so an edit shows at once instead of after the cache expires.
 */
@Component
public class DataVersion extends OncePerRequestFilter {

    private static final AtomicLong VERSION = new AtomicLong();

    public static long current() { return VERSION.get(); }

    /** For changes made outside a web request, such as an email sync. */
    public static void bump() { VERSION.incrementAndGet(); }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || "GET".equals(request.getMethod())
            || "HEAD".equals(request.getMethod()) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            if (response.getStatus() < 400) bump();
        }
    }
}
