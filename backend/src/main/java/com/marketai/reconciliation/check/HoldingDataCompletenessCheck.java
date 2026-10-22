package com.marketai.reconciliation.check;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.portfolio.util.XirrCalculator;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Holdings whose numbers the app cannot stand behind because an input is missing: the trades
 * that explain the units, the purchase date, the scheme link, or a current price.
 *
 * <p>Each gap is named with what it breaks and how to close it, since every figure downstream —
 * XIRR, tax, valuation, fund ranking — silently depends on it. Like every check here it only
 * reports; it never fills a gap with a guess.
 */
@Component
@RequiredArgsConstructor
public class HoldingDataCompletenessCheck implements ReconciliationCheck {

    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;

    @Override public String id() { return "HOLDING_DATA_COMPLETENESS"; }
    @Override public String domain() { return "PORTFOLIO"; }
    @Override public String description() {
        return "A holding is missing an input its figures depend on — trades that explain its units, "
             + "a purchase date, an AMFI scheme link, or a current price";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<Holding> holdings = new ArrayList<>();
        for (Portfolio p : portfolioRepository.findByUserId(userId)) holdings.addAll(holdingRepository.findOpenByPortfolioId(p.getId()));
        if (holdings.isEmpty()) return List.of();

        Map<Long, List<Transaction>> txns = transactionRepository
                .findByHoldingIdInOrderByTransactionDateAscIdAsc(holdings.stream().map(Holding::getId).toList())
                .stream().collect(Collectors.groupingBy(t -> t.getHolding().getId()));

        LocalDate today = LocalDate.now();
        List<ReconciliationIssue> issues = new ArrayList<>();
        for (Holding h : holdings) {
            boolean mf = h.getSymbol() != null && h.getSymbol().toUpperCase().endsWith(".MF");
            String label = String.format("'%s' (%s)", h.getName() != null ? h.getName() : h.getSymbol(), h.getSymbol());
            String fix = mf ? "Import a detailed CAS (with transactions) for this folio" : "Import the broker's tradebook or contract notes";
            List<Transaction> own = txns.getOrDefault(h.getId(), List.of());

            if (own.isEmpty()) {
                issues.add(issue("NO_TRANSACTION_HISTORY", "MEDIUM", h, String.format(
                    "%s has no trades on record — only a balance. XIRR, holding period and capital-gains tax can't be worked out. %s.",
                    label, fix)));
            } else if (!XirrCalculator.ledgerExplainsUnits(h, own)) {
                issues.add(issue("LEDGER_INCOMPLETE", "MEDIUM", h, String.format(
                    "%s: the trades on record add up to %s units, but %s are held. Some purchases or sales are missing, "
                        + "so XIRR falls back to the statement figure and tax lots are incomplete. %s.",
                    label, units(XirrCalculator.ledgerUnits(own)), units(h.getQuantity()), fix)));
            }

            if (h.getBuyDate() == null) {
                issues.add(issue("NO_PURCHASE_DATE", "LOW", h, String.format(
                    "%s has no purchase date, so short- vs long-term tax and the holding period are unknown. %s.", label, fix)));
            }

            if (mf && (h.getAmfiSchemeCode() == null || h.getAmfiSchemeCode().isBlank())) {
                issues.add(issue("MF_NOT_LINKED", "MEDIUM", h, String.format(
                    "%s isn't matched to an AMFI scheme, so its NAV is not refreshed and it can't be compared with its "
                        + "category. Check the fund name matches the scheme name on AMFI, including plan (Direct/Regular) and option (Growth/IDCW).",
                    label)));
            }

            switch (h.valuationBasis(today)) {
                case COST -> issues.add(issue("NO_CURRENT_PRICE", "HIGH", h, String.format(
                    "%s has never received a price, so it is valued at cost — its gain or loss is not shown. %s",
                    label, mf ? "Linking it to its AMFI scheme fixes this." : "Check the ticker symbol is the NSE/BSE one.")));
                case STALE -> issues.add(issue("STALE_PRICE", "MEDIUM", h, String.format(
                    "%s was last priced %s, so its value is out of date. %s",
                    label, h.getPriceAsOf() != null ? "on " + h.getPriceAsOf() : "on an unknown date",
                    mf ? "The nightly NAV refresh isn't reaching it — check the scheme link." : "Check the ticker still trades (renamed, merged or delisted).")));
                default -> { }
            }
        }
        return issues;
    }

    private ReconciliationIssue issue(String type, String severity, Holding h, String description) {
        return ReconciliationIssue.builder().domain(domain()).type(type).severity(severity)
                .referenceId(h.getId()).description(description).build();
    }

    private static String units(BigDecimal v) {
        return v == null ? "0" : v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
