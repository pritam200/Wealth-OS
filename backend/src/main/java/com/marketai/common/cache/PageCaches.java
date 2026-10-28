package com.marketai.common.cache;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** One {@link PageCache} per page, created on first use. */
@Component
@RequiredArgsConstructor
public class PageCaches {

    private final EntityManagerFactory emf;
    private final ConcurrentHashMap<String, PageCache<?>> caches = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <V> PageCache<V> of(String name, Duration fresh, boolean waitAfterChange) {
        return (PageCache<V>) caches.computeIfAbsent(name, n -> new PageCache<V>(n, fresh, waitAfterChange, emf));
    }
}
