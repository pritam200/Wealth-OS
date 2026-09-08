package com.marketai.networth.service;

import com.marketai.networth.entity.NetWorthSnapshot;
import com.marketai.networth.repository.NetWorthSnapshotRepository;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NetWorthService {

    private final NetWorthSnapshotRepository repo;
    private final PortfolioContextService portfolioContextService;

    /**
     * Upsert today's snapshot (one per user per day) — always computed server-side from
     * {@link PortfolioContextService}, the single canonical net-worth calculation. Never
     * accepts a caller-supplied total: a client-computed number here previously let the
     * persisted "history" silently diverge from whatever the rest of the app considered
     * correct, which is exactly the kind of cross-tab disagreement this was rewritten to stop.
     */
    public NetWorthSnapshot record(Long userId) {
        PortfolioContext ctx = portfolioContextService.build(userId);
        LocalDate today = LocalDate.now();
        NetWorthSnapshot snap = repo.findByUserIdAndSnapshotDate(userId, today)
            .orElse(NetWorthSnapshot.builder().userId(userId).snapshotDate(today).build());
        snap.setTotalAssets(ctx.getTotalAssets());
        snap.setNetWorth(ctx.getNetWorth());
        return repo.save(snap);
    }

    public List<NetWorthSnapshot> series(Long userId) {
        return repo.findByUserIdOrderBySnapshotDateAsc(userId);
    }
}
