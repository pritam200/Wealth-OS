package com.marketai.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PageCacheTest {

    @Test
    @DisplayName("A fresh result is reused; after a change, a page that must be current waits for a new one")
    void reusedUntilAChange() {
        AtomicInteger runs = new AtomicInteger();
        PageCache<Integer> cache = new PageCache<>("t", Duration.ofMinutes(5), true, null);
        assertThat(cache.get(1L, runs::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get(1L, runs::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get(2L, runs::incrementAndGet)).isEqualTo(2);
        DataVersion.bump();
        assertThat(cache.get(1L, runs::incrementAndGet)).isEqualTo(3);
    }

    @Test
    @DisplayName("An advisory page shows its last result after a change while the new one is worked out")
    void staleShownWhileRefreshing() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        PageCache<Integer> cache = new PageCache<>("t2", Duration.ofMinutes(5), false, null);
        assertThat(cache.get(1L, runs::incrementAndGet)).isEqualTo(1);
        DataVersion.bump();
        assertThat(cache.get(1L, runs::incrementAndGet)).isEqualTo(1);
        for (int i = 0; i < 50 && runs.get() < 2; i++) Thread.sleep(20);
        Thread.sleep(50);
        assertThat(cache.get(1L, runs::incrementAndGet)).isEqualTo(2);
    }
}
