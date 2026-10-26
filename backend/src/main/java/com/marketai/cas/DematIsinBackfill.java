package com.marketai.cas;

import com.marketai.dataplatform.domain.FinancialAsset;
import com.marketai.dataplatform.repo.FinancialAssetRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.reconciliation.check.StatementPositions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Gives a share holding the ISIN its demat statement prints for it, so it can be checked against
 * the depository by ISIN from then on. Only a holding with no ISIN is touched, only when exactly
 * one statement line fits it — by the symbol the security is known under, else by the company
 * name with the legal suffix ignored — and never when another holding in the same portfolio
 * already carries that ISIN.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DematIsinBackfill {

    public record Line(String isin, String name) {}

    private final PortfolioRepository portfolios;
    private final HoldingRepository holdings;
    private final FinancialAssetRepository assets;

    /** @return how many holdings were given an ISIN */
    @Transactional
    public int backfill(Long userId, List<Line> lines) {
        Map<String, List<String>> bySymbol = new HashMap<>(), byName = new HashMap<>();
        for (Line l : lines) {
            if (l.isin() == null || l.isin().startsWith("INF")) continue;   // funds are linked by scheme, not here
            String isin = l.isin().trim().toUpperCase();
            String sym = assets.findFirstByIsin(isin).map(FinancialAsset::getSymbol).map(StatementPositions::bareSymbol).orElse(null);
            if (sym != null) bySymbol.computeIfAbsent(sym, k -> new ArrayList<>()).add(isin);
            String name = StatementPositions.companyKey(l.name());
            if (name != null) byName.computeIfAbsent(name, k -> new ArrayList<>()).add(isin);
        }
        int filled = 0;
        for (Portfolio p : portfolios.findByUserId(userId)) {
            List<Holding> own = holdings.findByPortfolioId(p.getId());
            Set<String> taken = new HashSet<>();
            for (Holding h : own) if (h.getIsin() != null) taken.add(h.getIsin().trim().toUpperCase());
            for (Holding h : own) {
                if (h.getIsin() != null || StatementPositions.isFund(h)) continue;
                String isin = only(bySymbol.get(StatementPositions.bareSymbol(h.getSymbol())));
                if (isin == null) isin = only(byName.get(StatementPositions.companyKey(h.getName())));
                if (isin == null || !taken.add(isin)) continue;
                h.setIsin(isin);
                holdings.save(h);
                filled++;
                log.info("Holding {} given ISIN {} from the demat statement", h.getId(), isin);
            }
        }
        return filled;
    }

    private static String only(List<String> isins) {
        return isins != null && isins.size() == 1 ? isins.get(0) : null;
    }
}
