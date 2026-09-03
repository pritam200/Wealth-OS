package com.marketai.networth.service;

import com.marketai.networth.entity.NetWorthSnapshot;
import com.marketai.networth.repository.NetWorthSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NetWorthService {

    private final NetWorthSnapshotRepository repo;

    /** Upsert today's snapshot (one per user per day). */
    public NetWorthSnapshot record(Long userId, BigDecimal totalAssets, BigDecimal netWorth) {
        LocalDate today = LocalDate.now();
        NetWorthSnapshot snap = repo.findByUserIdAndSnapshotDate(userId, today)
            .orElse(NetWorthSnapshot.builder().userId(userId).snapshotDate(today).build());
        snap.setTotalAssets(totalAssets);
        snap.setNetWorth(netWorth);
        return repo.save(snap);
    }

    public List<NetWorthSnapshot> series(Long userId) {
        return repo.findByUserIdOrderBySnapshotDateAsc(userId);
    }
}
