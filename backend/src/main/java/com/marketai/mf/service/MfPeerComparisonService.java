package com.marketai.mf.service;

import com.marketai.amfi.dto.AmfiNavResult;
import com.marketai.amfi.service.AmfiNavService;
import com.marketai.mf.dto.MfPerformanceMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Where a fund stands among its SEBI-category peers, from NAV history we hold.
 *
 * <p>Peers are the open-ended, growth-option schemes in the same AMFI category and the same
 * plan (direct vs regular — their expense ratios differ, so mixing them would skew ranks).
 * Peer NAV histories are fetched in the background the first time a category is asked for;
 * until enough have landed the comparison says so instead of ranking against a handful.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MfPeerComparisonService {

    /** Fewer ranked peers than this and a percentile means little. */
    static final int MIN_PEERS = 8;
    /** Upper bound on peers fetched per category — keeps the first load bounded. */
    private static final int MAX_PEERS = 80;
    private static final long SNAPSHOT_TTL_HOURS = 12;
    private static final long NAV_STALE_DAYS = 10;

    private final AmfiNavService amfiNavService;
    private final MfNavHistoryService navHistoryService;
    private final MfPerformanceService performanceService;

    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();

    private record Snapshot(Instant at, List<PeerMetrics> peers, int universe) {}
    public record PeerMetrics(String schemeCode, String name, MfPerformanceMetrics m) {}

    /** One trailing window: the fund's figure, the peer median, and its rank (1 = best). */
    public record WindowRank(String window, BigDecimal fund, BigDecimal categoryMedian, int rank, int ranked) {
        /** 0 = best, 100 = worst. */
        public int percentileFromTop() { return ranked <= 1 ? 0 : (int) Math.round((rank - 1) * 100.0 / (ranked - 1)); }
        public String quartileLabel() {
            int p = percentileFromTop();
            return p <= 25 ? "top quartile" : p <= 50 ? "second quartile" : p <= 75 ? "third quartile" : "bottom quartile";
        }
    }

    public record Comparison(String schemeCode, String schemeName, String category, String plan,
                             int peersInCategory, int peersWithHistory, boolean peerDataLoading,
                             MfPerformanceMetrics fund, List<WindowRank> ranks, List<PeerMetrics> leaders) {
        public Optional<WindowRank> rank(String window) { return ranks.stream().filter(r -> r.window.equals(window)).findFirst(); }
    }

    /** Empty when the scheme is not in the AMFI list (unlinked, or the list has not loaded yet). */
    public Optional<Comparison> compare(String schemeCode) {
        if (schemeCode == null || schemeCode.isBlank()) return Optional.empty();
        AmfiNavResult self = amfiNavService.findByCode(schemeCode.trim());
        if (self == null || self.getCategory() == null) return Optional.empty();

        boolean direct = isDirect(self.getSchemeName());
        List<AmfiNavResult> universe = amfiNavService.all().stream()
                .filter(r -> self.getCategory().equals(r.getCategory()))
                .filter(r -> r.getSchemeType() == null || "Open Ended".equals(r.getSchemeType()))
                .filter(r -> isGrowth(r.getSchemeName()) && isDirect(r.getSchemeName()) == direct)
                .filter(r -> !r.getSchemeCode().equals(self.getSchemeCode()))
                .limit(MAX_PEERS)
                .toList();

        String key = self.getCategory() + "|" + direct;
        Snapshot snap = snapshots.get(key);
        if (snap == null || snap.at().isBefore(Instant.now().minus(SNAPSHOT_TTL_HOURS, ChronoUnit.HOURS))) {
            snap = buildSnapshot(universe);
            snapshots.put(key, snap);
            if (snap.peers().size() < universe.size()) loadInBackground(key, universe);
        }

        var lastNav = navHistoryService.latestNavDate(self.getSchemeCode());
        if (lastNav.isEmpty() || lastNav.get().isBefore(LocalDate.now().minusDays(3))) {
            navHistoryService.fetchAndStoreHistory(self.getSchemeCode());
        }
        MfPerformanceMetrics fund = performanceService.getPerformance(self.getSchemeCode());

        List<WindowRank> ranks = new ArrayList<>();
        rankWindow("1Y", fund, snap.peers(), MfPerformanceMetrics::getReturn1Y).ifPresent(ranks::add);
        rankWindow("3Y", fund, snap.peers(), MfPerformanceMetrics::getReturn3YCagr).ifPresent(ranks::add);
        rankWindow("5Y", fund, snap.peers(), MfPerformanceMetrics::getReturn5YCagr).ifPresent(ranks::add);

        // Leaders by 3Y CAGR (1Y when the category is too young for 3Y) — candidates for a switch.
        Function<MfPerformanceMetrics, BigDecimal> leadBy = snap.peers().stream()
                .filter(p -> p.m().getReturn3YCagr() != null).count() >= MIN_PEERS
                ? MfPerformanceMetrics::getReturn3YCagr : MfPerformanceMetrics::getReturn1Y;
        List<PeerMetrics> leaders = snap.peers().stream()
                .filter(p -> leadBy.apply(p.m()) != null)
                .sorted(Comparator.comparing((PeerMetrics p) -> leadBy.apply(p.m())).reversed())
                .limit(3).toList();

        return Optional.of(new Comparison(self.getSchemeCode(), self.getSchemeName(), self.getCategory(),
                direct ? "Direct" : "Regular", universe.size(), snap.peers().size(),
                loading.contains(key) || snap.peers().size() < universe.size(), fund, ranks, leaders));
    }

    private Optional<WindowRank> rankWindow(String window, MfPerformanceMetrics fund, List<PeerMetrics> peers,
                                            Function<MfPerformanceMetrics, BigDecimal> metric) {
        BigDecimal mine = metric.apply(fund);
        if (mine == null) return Optional.empty();
        List<BigDecimal> values = new ArrayList<>(peers.stream().map(p -> metric.apply(p.m())).filter(Objects::nonNull).toList());
        if (values.size() < MIN_PEERS) return Optional.empty();
        long better = values.stream().filter(v -> v.compareTo(mine) > 0).count();
        values.add(mine);
        values.sort(Comparator.naturalOrder());
        BigDecimal median = values.get(values.size() / 2);
        return Optional.of(new WindowRank(window, mine, median, (int) better + 1, values.size()));
    }

    /** Metrics for peers whose history is already stored — no network on the request path. */
    private Snapshot buildSnapshot(List<AmfiNavResult> universe) {
        List<PeerMetrics> out = new ArrayList<>();
        for (AmfiNavResult r : universe) {
            MfPerformanceMetrics m = performanceService.getPerformance(r.getSchemeCode());
            if (m.getObservationCount() > 0) out.add(new PeerMetrics(r.getSchemeCode(), r.getSchemeName(), m));
        }
        return new Snapshot(Instant.now(), out, universe.size());
    }

    private void loadInBackground(String key, List<AmfiNavResult> universe) {
        if (!loading.add(key)) return;
        Thread t = new Thread(() -> {
            try {
                for (AmfiNavResult r : universe) {
                    var last = navHistoryService.latestNavDate(r.getSchemeCode());
                    if (last.isEmpty() || last.get().isBefore(LocalDate.now().minusDays(NAV_STALE_DAYS))) {
                        navHistoryService.fetchAndStoreHistory(r.getSchemeCode());
                    }
                }
                snapshots.put(key, buildSnapshot(universe));
                log.info("MF peer histories loaded for {} ({} schemes)", key, universe.size());
            } catch (Exception e) {
                log.warn("MF peer history load failed for {}: {}", key, e.getMessage());
            } finally {
                loading.remove(key);
            }
        }, "mf-peer-load");
        t.setDaemon(true);
        t.start();
    }

    static boolean isDirect(String name) {
        return name != null && name.toLowerCase(Locale.ENGLISH).contains("direct");
    }

    static boolean isGrowth(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ENGLISH);
        return n.contains("growth") && !n.contains("idcw") && !n.contains("dividend") && !n.contains("bonus");
    }
}
