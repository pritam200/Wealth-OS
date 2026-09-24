package com.marketai.reconciliation.check;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MF holdings with no folio number recorded at all.
 *
 * <p>{@code ParsedEmailImporter.importMf} matches/creates a holding by a symbol derived from the
 * fund name, not by folio — that matching behaviour is unchanged here. But a fund name alone
 * cannot tell two same-named schemes apart across different AMCs or the same AMC across two of
 * the user's accounts, and the folio number is the one identifier that can. A holding with no
 * folio at all is therefore not disambiguated from a same-named fund at a different AMC/account
 * that might arrive later — a real risk of silently merging two distinct positions into one
 * symbol.
 *
 * <p>This check only reports the gap. It does not change how holdings are matched or created —
 * doing that is a separate, larger decision (and risks its own regressions) that the "add
 * visibility, never auto-correct" mandate for this package does not license on its own.
 */
@Component
@RequiredArgsConstructor
public class UnlinkedIdentifierCheck implements ReconciliationCheck {

    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;

    @Override public String id() { return "PORTFOLIO_UNLINKED_IDENTIFIER"; }
    @Override public String domain() { return "PORTFOLIO"; }
    @Override public String description() {
        return "An MF holding has no folio number recorded, so it cannot be disambiguated from "
             + "a same-named scheme at a different AMC or account";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<ReconciliationIssue> issues = new ArrayList<>();

        for (Portfolio portfolio : portfolioRepository.findByUserId(userId)) {
            for (Holding h : holdingRepository.findByPortfolioId(portfolio.getId())) {
                if (h.getSymbol() == null || !h.getSymbol().endsWith(".MF")) continue;
                if (h.getFolio() != null && !h.getFolio().isBlank()) continue;

                issues.add(ReconciliationIssue.builder()
                    .domain(domain()).type("UNLINKED_IDENTIFIER").severity("LOW")
                    .referenceId(h.getId())
                    .description(String.format(
                        "MF holding '%s' (symbol %s) has no folio number recorded. Without it, "
                            + "this holding cannot be told apart from a same-named scheme at a "
                            + "different AMC or account if one is imported later. Add the folio "
                            + "number when you know it.",
                        h.getName(), h.getSymbol()))
                    .build());
            }
        }
        return issues;
    }
}
