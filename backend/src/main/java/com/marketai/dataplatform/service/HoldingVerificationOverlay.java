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
            var health = quality.health(userId, false);
            if (health.holdings().isEmpty()) return;
            for (HoldingDto h : holdings) {
                // The same fund can sit in several accounts: surface the most concerning state, then the least verified.
                var m = health.holdings().stream().filter(x -> matches(h, x))
                    .min(java.util.Comparator.comparingInt(x -> severity(x.state()))).orElse(null);
                if (m == null) continue;
                h.setVerificationState(m.state());
                h.setVerificationSource(m.source());
                h.setLastVerifiedAt(m.lastVerifiedAt());
                h.setInstitutionQuantity(m.reportedQuantity());
            }
        } catch (Exception e) {
            log.debug("Verification overlay unavailable: {}", e.getClass().getSimpleName());
        }
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
