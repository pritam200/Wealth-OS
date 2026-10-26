package com.marketai.market.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Makes "one stored bar per symbol per day" a database rule rather than a hope.
 *
 * <p>The table is built by Hibernate from the entity, which never declared the daily key
 * unique, and concurrent writers left tens of thousands of duplicate bars. A unique index can't
 * be added while duplicates exist — Hibernate's schema update would fail on it — so this does
 * it explicitly at startup, on PostgreSQL only:
 * <ol>
 *   <li>rows with no interval are marked daily (they always were);</li>
 *   <li>if the unique daily index already exists, upserts are switched on and nothing else runs;</li>
 *   <li>if duplicates remain and {@code app.market.dedupe-price-history} is off (the default),
 *       it logs how many and stops — nothing is deleted without being asked;</li>
 *   <li>with it on, every duplicate but the most recently updated copy is deleted (the copy
 *       every reader already uses), then the index is created and upserts are switched on.</li>
 * </ol>
 * Failures are logged and never stop the application: reads already collapse duplicates.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PriceHistoryIntegrity {

    static final String DAILY_INDEX = "uq_ph_daily_symbol_date";
    static final String INTRADAY_INDEX = "uq_ph_intraday_symbol_bar";

    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final MarketDataService marketData;

    @Value("${app.market.dedupe-price-history:false}")
    private boolean dedupe;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        // Own thread: a dedupe over a large table must not hold up the other startup work.
        Thread t = new Thread(this::run, "price-history-integrity");
        t.setDaemon(true);
        t.start();
    }

    void run() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) return;
        try {
            String db = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            if (db == null || !db.toLowerCase().contains("postgres")) return;

            int unlabelled = jdbc.update("update price_history set bar_interval = '1d' where bar_interval is null");
            if (unlabelled > 0) log.info("Marked {} price bar(s) with no interval as daily", unlabelled);

            if (indexExists(jdbc, DAILY_INDEX)) {
                marketData.setDailyKeyUnique(true);
                return;
            }

            Long duplicates = jdbc.queryForObject(
                "select count(*) - count(distinct (symbol, date)) from price_history where bar_interval = '1d'", Long.class);
            long dupes = duplicates != null ? duplicates : 0;
            if (dupes > 0 && !dedupe) {
                log.warn("price_history holds {} duplicate daily bar(s). Readers ignore them, but the unique index that stops new ones "
                    + "can't be added until they're gone. Take a backup, then restart once with PRICE_HISTORY_DEDUPE=true "
                    + "(app.market.dedupe-price-history) to delete them, keeping the most recently updated copy of each bar.", dupes);
                return;
            }
            if (dupes > 0) {
                int deleted = jdbc.update("""
                    delete from price_history p using (
                        select id, row_number() over (partition by symbol, date
                            order by coalesce(updated_at, ingested_at) desc nulls last, id desc) as rn
                        from price_history where bar_interval = '1d') d
                    where p.id = d.id and d.rn > 1""");
                log.warn("Deleted {} duplicate daily price bar(s), keeping the most recently updated copy of each", deleted);
            }
            jdbc.execute("create unique index if not exists " + DAILY_INDEX
                + " on price_history (symbol, date) where bar_interval = '1d'");
            // Intraday bars share a date, so their key is the bar's start. None are stored yet.
            jdbc.execute("create unique index if not exists " + INTRADAY_INDEX
                + " on price_history (symbol, bar_interval, bar_start) where bar_interval <> '1d'");
            marketData.setDailyKeyUnique(true);
            log.info("price_history: one daily bar per symbol and date is now enforced by {}", DAILY_INDEX);
        } catch (Exception e) {
            log.warn("price_history integrity check did not complete: {}", e.getMessage());
        }
    }

    private static boolean indexExists(JdbcTemplate jdbc, String name) {
        Integer n = jdbc.queryForObject(
            "select count(*) from pg_indexes where tablename = 'price_history' and indexname = ?", Integer.class, name);
        return n != null && n > 0;
    }
}
