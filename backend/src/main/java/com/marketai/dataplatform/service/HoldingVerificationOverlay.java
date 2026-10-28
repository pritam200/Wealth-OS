package com.marketai.dataplatform.service;

import com.marketai.portfolio.dto.PortfolioSummaryDto.HoldingDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Marks portfolio holdings with what the canonical ledger knows about them: whether an institution
 * has confirmed the position, from which source and when, and the institution's own quantity. The
 * figures the portfolio computes are never replaced. Failure to annotate leaves the holdings as they
 * were, since this is decoration on a screen that must keep working.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HoldingVerificationOverlay {

    private final DataQualityService quality;

    public void annotate(Long userId, List<HoldingDto> holdings) {
        if (holdings == null || holdings.isEmpty()) return;
        try {
            var health = quality.recentHealth(userId);
            if (health.holdings().isEmpty()) return;
            for (HoldingDto h : holdings) {
                // The same fund can sit in several accounts: surface the most concerning state, then the least verified.
                var all = health.holdings().stream().filter(x -> matches(h, x)).toList();
                var m = all.stream().min(java.util.Comparator.comparingInt(x -> severity(stateFor(h, x, all)))).orElse(null);
                if (m == null) continue;
                h.setVerificationState(stateFor(h, m, all));
                h.setVerificationSource(m.source());
                h.setLastVerifiedAt(m.lastVerifiedAt());
                h.setInstitutionQuantity(m.reportedQuantity());
            }
        } catch (Exception e) {
            log.debug("Verification overlay unavailable: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * A demat statement line has no ledger of its own, so it is compared with the holding here: the
     * holding is verified when its quantity is the depository's — in one demat account, or in all
     * of them together — and otherwise left unverified with the depository's quantity shown, since a
     * trade after the statement date explains a difference as well as an error does. The
     * Reconciliation Center compares the two as of the statement date.
     */
    static String stateFor(HoldingDto h, DataQualityService.HoldingHealth x, List<DataQualityService.HoldingHealth> all) {
        if (!"REPORTED_ONLY".equals(x.state())) return x.state();
        if (h.getQuantity() == null) return "UNVERIFIED";
        List<java.math.BigDecimal> reported = all.stream().filter(o -> "REPORTED_ONLY".equals(o.state()) && o.reportedQuantity() != null)
            .map(DataQualityService.HoldingHealth::reportedQuantity).toList();
        java.math.BigDecimal total = reported.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        boolean same = h.getQuantity().compareTo(total) == 0 || reported.stream().anyMatch(q -> h.getQuantity().compareTo(q) == 0);
        return same ? "VERIFIED" : "UNVERIFIED";
    }

    private static int severity(String state) {
        return "NEEDS_RECONCILIATION".equals(state) ? 0 : "UNVERIFIED".equals(state) ? 1 : 2;
    }

    static boolean matches(HoldingDto h, DataQualityService.HoldingHealth x) {
        if (h.getIsin() != null && x.isin() != null) return h.getIsin().trim().equalsIgnoreCase(x.isin().trim());
        String a = bare(h.getSymbol()), b = bare(x.symbol());
        return a != null && a.equals(b);
    }

    private static String bare(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT).replaceAll("\\.(NS|BO|MF)$", "");
    }
}
