package com.marketai.common.cache;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The last result of a slow, read-only page computation, per user. Within {@code fresh} it is
 * returned as is. Older than that, it is still returned at once while a recomputation runs in
 * the background, so a page opened again shows immediately and is current on the next visit.
 * After a change (see {@link DataVersion}), or on the first request, the caller waits for a
 * fresh result. Concurrent requests for the same user share one computation.
 */
@Slf4j
public class PageCache<V> {

    private record Entry<V>(V value, long at, long version) {}

    private final String name;
    private final long freshMs;
    private final boolean waitAfterChange;
    private final ConcurrentHashMap<Long, Entry<V>> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<V>> running = new ConcurrentHashMap<>();
    private final Executor background;
    private final EntityManagerFactory emf;

    /**
     * @param waitAfterChange true when a stale result after an edit would mislead (net worth just
     *                        after adding a holding); false when it is advisory and slow to build,
     *                        so the last result is shown while the new one is worked out.
     */
    public PageCache(String name, Duration fresh, boolean waitAfterChange, EntityManagerFactory emf) {
        this.name = name;
        this.emf = emf;
        this.freshMs = fresh.toMillis();
        this.waitAfterChange = waitAfterChange;
        this.background = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "page-cache-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    public V get(Long userId, Supplier<V> compute) {
        Entry<V> e = entries.get(userId);
        long now = System.currentTimeMillis();
        boolean changed = e != null && e.version != DataVersion.current();
        if (e != null && !changed && now - e.at < freshMs) return e.value;
        if (e == null || (changed && waitAfterChange)) return await(start(userId, compute, false));
        start(userId, compute, true);
        return e.value;
    }

    /** Drops the stored result, so the next request computes a new one. */
    public void evict(Long userId) { entries.remove(userId); }

    private CompletableFuture<V> start(Long userId, Supplier<V> compute, boolean inBackground) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> already = running.putIfAbsent(userId, mine);
        if (already != null) return already;
        long version = DataVersion.current();
        long started = System.currentTimeMillis();
        Runnable job = () -> {
            try {
                V v = compute.get();
                if (v != null) entries.put(userId, new Entry<>(v, started, version));
                mine.complete(v);
            } catch (RuntimeException ex) {
                log.warn("{} for user {} could not be computed: {}", name, userId, ex.getMessage());
                mine.completeExceptionally(ex);
            } finally {
                running.remove(userId, mine);
            }
        };
        // The first request computes on its own thread, which already has the request's session;
        // a background refresh opens one, as a web request would.
        if (inBackground) background.execute(() -> InSession.run(emf, job));
        else job.run();
        return mine;
    }

    private V await(CompletableFuture<V> f) {
        try {
            return f.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " was interrupted", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(ex.getCause());
        }
    }
}
