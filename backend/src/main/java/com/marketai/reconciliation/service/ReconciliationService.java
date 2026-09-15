package com.marketai.reconciliation.service;

import com.marketai.networth.entity.NetWorthSnapshot;
import com.marketai.networth.repository.NetWorthSnapshotRepository;
import com.marketai.portfolio.dto.IntegrityReportDto;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.reconciliation.dto.ReconciliationReportDto;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import com.marketai.tracking.service.TrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generalizes the "flag it, never silently show a wrong number" pattern that previously
 * existed only for portfolio holdings ({@link PortfolioService#checkIntegrity}) to every
 * financial domain: FD/RD lifecycle problems, and net worth drift between what the app is
 * showing right now and what was last persisted for history — the one check that would catch
 * a future regression of the single-source-of-truth fix in {@code PortfolioContextService}.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final PortfolioService portfolioService;
    private final TrackingService trackingService;
    private final PortfolioContextService portfolioContextService;
    private final NetWorthSnapshotRepository netWorthSnapshotRepository;
    private final com.marketai.reconciliation.check.ReconciliationRegistry checkRegistry;

    // A drift smaller than this is just normal same-day market movement (live stock prices
    // refresh continuously) or rounding — not a reconciliation problem worth surfacing.
    private static final BigDecimal DRIFT_ABSOLUTE_FLOOR = new BigDecimal("1000");
    private static final double DRIFT_RELATIVE_TOLERANCE = 0.01; // 1%

    public ReconciliationReportDto checkAll(Long userId) {
        List<ReconciliationIssue> issues = new ArrayList<>();

        IntegrityReportDto portfolioReport = portfolioService.checkIntegrity(userId);
        for (IntegrityReportDto.Issue issue : portfolioReport.getIssues()) {
            issues.add(ReconciliationIssue.builder()
                .domain("PORTFOLIO")
                .type(issue.getType())
                .description(issue.getDescription())
                .referenceId(issue.getHoldingId())
                .severity("MISMATCHED_TICKER".equals(issue.getType()) || "DUPLICATE_SYMBOL".equals(issue.getType()) ? "MEDIUM" : "HIGH")
                .build());
        }

        issues.addAll(trackingService.checkFdRdIntegrity(userId));
        issues.addAll(checkNetWorthDrift(userId));

        // Registered checks — ledger, ingestion and review-queue integrity. Added as a registry
        // rather than more inline calls so the set of checks is enumerable: a user asking
        // "what does this actually verify?" gets an answer that cannot drift from the code.
        issues.addAll(checkRegistry.runAll(userId));

        return ReconciliationReportDto.builder().issueCount(issues.size()).issues(issues).build();
    }

    private List<ReconciliationIssue> checkNetWorthDrift(Long userId) {
        List<NetWorthSnapshot> history = netWorthSnapshotRepository.findByUserIdOrderBySnapshotDateAsc(userId);
        if (history.isEmpty()) return Collections.emptyList();
        NetWorthSnapshot latest = history.get(history.size() - 1);
        if (latest.getNetWorth() == null) return Collections.emptyList();

        PortfolioContext current = portfolioContextService.build(userId);
        BigDecimal diff = current.getNetWorth().subtract(latest.getNetWorth()).abs();
        BigDecimal tolerance = latest.getNetWorth().abs()
            .multiply(BigDecimal.valueOf(DRIFT_RELATIVE_TOLERANCE))
            .max(DRIFT_ABSOLUTE_FLOOR);
        if (diff.compareTo(tolerance) <= 0) return Collections.emptyList();

        return Collections.singletonList(ReconciliationIssue.builder()
            .domain("NET_WORTH").type("NET_WORTH_DRIFT").severity("HIGH")
            .description(String.format(
                "Today's computed net worth (₹%s) differs from the last recorded snapshot (₹%s, %s) by more than expected — every screen should be reading from the same source, so this gap likely means a real data problem rather than normal market movement.",
                current.getNetWorth(), latest.getNetWorth(), latest.getSnapshotDate()))
            .build());
    }
}
