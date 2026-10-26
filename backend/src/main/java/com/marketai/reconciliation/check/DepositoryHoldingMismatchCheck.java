package com.marketai.reconciliation.check;

import com.marketai.cas.CasImportService;
import com.marketai.dataplatform.domain.AccountType;
import com.marketai.dataplatform.domain.BasisType;
import com.marketai.dataplatform.domain.FinancialAccount;
import com.marketai.dataplatform.domain.FinancialAsset;
import com.marketai.dataplatform.domain.HoldingSnapshot;
import com.marketai.dataplatform.repo.FinancialAccountRepository;
import com.marketai.dataplatform.repo.FinancialAssetRepository;
import com.marketai.dataplatform.repo.HoldingSnapshotRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The holdings built from trades, checked against the depository's (NSDL/CDSL) own statement of
 * what is in the user's demat accounts. Trades are booked from contract notes; the depository is
 * the independent record, so a contract note that never arrived or was misread shows up here as
 * a difference in shares.
 *
 * <p>Securities are matched by ISIN — the identifier the depository prints — and, for a holding
 * that has no ISIN yet, by its symbol where the security is known under one, then by the
 * company name with only the legal suffix ignored. Every demat account in the latest statement
 * is added up per security, since a share bought through two brokers sits in two accounts but is
 * one position. A security missing from the latest statement is held at zero there.
 *
 * <p>The portfolio side is the position its trades give on the statement date, allowing for
 * trades still settling. Like every check here it only reports: it never changes a holding.
 */
@Component
@RequiredArgsConstructor
public class DepositoryHoldingMismatchCheck implements ReconciliationCheck {

    static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

    private final FinancialAccountRepository accountRepository;
    private final HoldingSnapshotRepository snapshotRepository;
    private final FinancialAssetRepository assetRepository;
    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;

    @Override public String id() { return "DEPOSITORY_HOLDING_MISMATCH"; }
    @Override public String domain() { return "PORTFOLIO"; }
    @Override public String description() {
        return "The shares your trades give on the date of your latest NSDL/CDSL demat statement differ from "
             + "what the depository says you held";
    }

    /** One security on the latest depository statement, across every demat account in it. */
    record Line(String isin, String name, String symbol, BigDecimal quantity) {}

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        Statement st = latestStatement(userId);
        if (st == null) return List.of();

        List<Holding> holdings = new ArrayList<>();
        Map<Long, String> holdingPortfolio = new HashMap<>();
        for (Portfolio p : portfolioRepository.findByUserId(userId)) {
            for (Holding h : holdingRepository.findByPortfolioId(p.getId())) {
                holdings.add(h);
                holdingPortfolio.put(h.getId(), p.getName());
            }
        }
        Map<Long, List<Transaction>> txns = holdings.isEmpty() ? Map.of() : transactionRepository
            .findByHoldingIdInOrderByTransactionDateAscIdAsc(holdings.stream().map(Holding::getId).toList())
            .stream().collect(Collectors.groupingBy(t -> t.getHolding().getId()));

        Set<String> fundIsins = holdings.stream().filter(StatementPositions::isFund)
            .map(Holding::getIsin).filter(Objects::nonNull).map(s -> s.trim().toUpperCase()).collect(Collectors.toSet());
        Map<String, List<Holding>> byIsin = match(st.lines(), holdings.stream().filter(h -> !StatementPositions.isFund(h)).toList());

        List<ReconciliationIssue> issues = new ArrayList<>();
        Set<Long> matchedIds = byIsin.values().stream().flatMap(List::stream).map(Holding::getId).collect(Collectors.toSet());
        for (Holding h : holdings) {
            if (StatementPositions.isFund(h) || matchedIds.contains(h.getId())) continue;
            List<BigDecimal> onDate = StatementPositions.acceptable(h, txns.getOrDefault(h.getId(), List.of()), st.date());
            if (onDate.stream().anyMatch(q -> q.signum() == 0)) continue;   // bought after the statement, or since sold
            issues.add(ReconciliationIssue.builder()
                .domain(domain()).type("DEPOSITORY_HOLDING_UNMATCHED").severity("MEDIUM")
                .referenceId(h.getId())
                .description(String.format(
                    "%s (%s): %s shares on record on %s, but nothing on your demat statement of that date matches it — "
                        + "it has no ISIN, and neither its symbol nor its name is on the statement. If it isn't a share "
                        + "(a futures or options trade read as one), remove it; otherwise add its ISIN.",
                    h.getName() != null ? h.getName() : h.getSymbol(), h.getSymbol(),
                    StatementPositions.units(onDate.get(0)), st.date()))
                .build());
        }
        Set<String> isins = new LinkedHashSet<>(st.lines().keySet());
        isins.addAll(byIsin.keySet());
        for (String isin : isins) {
            Line line = st.lines().get(isin);
            BigDecimal reported = line != null ? line.quantity() : BigDecimal.ZERO;
            List<Holding> matched = byIsin.getOrDefault(isin, List.of());
            if (matched.isEmpty()) {
                // A fund held in demat is reconciled against its registrar, not here.
                if (reported.signum() <= 0 || fundIsins.contains(isin)) continue;
                issues.add(ReconciliationIssue.builder()
                    .domain(domain()).type("DEPOSITORY_HOLDING_NOT_IN_PORTFOLIO").severity("HIGH")
                    .description(String.format(
                        "%s (ISIN %s): your demat statement dated %s shows %s shares, but no holding in your "
                            + "portfolio matches it. A contract note for its purchase is probably missing — "
                            + "import it from your broker, or add the holding.",
                        line.name() != null ? line.name() : isin, isin, st.date(), StatementPositions.units(reported)))
                    .build());
                continue;
            }

            // Each holding's acceptable positions on the statement date, combined across holdings.
            List<BigDecimal> totals = List.of(BigDecimal.ZERO);
            boolean anyWithoutTrades = false;
            for (Holding h : matched) {
                List<Transaction> own = txns.getOrDefault(h.getId(), List.of());
                anyWithoutTrades |= own.isEmpty();
                List<BigDecimal> mine = StatementPositions.acceptable(h, own, st.date());
                List<BigDecimal> next = new ArrayList<>();
                for (BigDecimal a : totals) for (BigDecimal b : mine) next.add(a.add(b));
                totals = next.stream().distinct().limit(64).toList();
            }
            if (totals.stream().anyMatch(t -> StatementPositions.same(t, reported, TOLERANCE))) continue;
            BigDecimal ledger = totals.get(0);   // as of the statement date itself

            Holding first = matched.get(0);
            String where = matched.size() == 1 ? "" : " (" + matched.size() + " holdings: " + matched.stream()
                .map(h -> holdingPortfolio.getOrDefault(h.getId(), h.getSymbol())).distinct()
                .collect(Collectors.joining(", ")) + ")";
            issues.add(ReconciliationIssue.builder()
                .domain(domain()).type(id()).severity("HIGH")
                .referenceId(first.getId())
                .description(String.format(
                    "%s (ISIN %s): your demat statement dated %s shows %s shares, but the trades on record give %s "
                        + "on that date%s. %s Check before changing anything.",
                    first.getName() != null ? first.getName() : first.getSymbol(), isin, st.date(),
                    StatementPositions.units(reported), StatementPositions.units(ledger), where,
                    anyWithoutTrades ? "Some of these holdings have no trades on record, so their current quantity was used."
                        : reported.compareTo(ledger) > 0 ? "A purchase, bonus or split may be missing from your contract notes."
                        : "A sale may be missing, or a trade was booked that never reached your demat account (an F&O trade read as shares, for one)."))
                .build());
        }
        return issues;
    }

    record Statement(LocalDate date, Map<String, Line> lines) {}

    /**
     * The latest depository statement: the demat accounts whose newest report carries the latest
     * date, and in them every security reported on that date. A security whose last report is
     * older dropped out of the statement, so it is held at zero now.
     */
    Statement latestStatement(Long userId) {
        Map<Long, FinancialAccount> demat = accountRepository.findByOwnerUserId(userId).stream()
            .filter(a -> a.getAccountType() == AccountType.DEMAT
                && CasImportService.DEMAT_INSTITUTION.equalsIgnoreCase(a.getInstitution()))
            .collect(Collectors.toMap(FinancialAccount::getId, Function.identity()));
        if (demat.isEmpty()) return null;
        List<HoldingSnapshot> reported = snapshotRepository.findByAccountIdIn(demat.keySet()).stream()
            .filter(s -> s.getBasis() == BasisType.INSTITUTION_REPORTED && s.getAsOfDate() != null && s.getQuantity() != null)
            .toList();
        if (reported.isEmpty()) return null;

        Map<Long, LocalDate> newestPerAccount = new HashMap<>();
        for (HoldingSnapshot s : reported) newestPerAccount.merge(s.getAccountId(), s.getAsOfDate(), (a, b) -> a.isAfter(b) ? a : b);
        LocalDate date = Collections.max(newestPerAccount.values());

        Map<Long, FinancialAsset> assets = assetRepository.findAllById(reported.stream().map(HoldingSnapshot::getAssetId).distinct().toList())
            .stream().collect(Collectors.toMap(FinancialAsset::getId, Function.identity()));
        Map<String, Line> lines = new LinkedHashMap<>();
        for (HoldingSnapshot s : reported) {
            if (!date.equals(newestPerAccount.get(s.getAccountId()))) continue;   // an account not in the latest statement
            FinancialAsset a = assets.get(s.getAssetId());
            if (a == null || a.getIsin() == null) continue;
            String isin = a.getIsin().trim().toUpperCase();
            BigDecimal qty = date.equals(s.getAsOfDate()) ? s.getQuantity() : BigDecimal.ZERO;
            lines.merge(isin, new Line(isin, a.getName(), a.getSymbol(), qty),
                (x, y) -> new Line(isin, x.name() != null ? x.name() : y.name(), x.symbol() != null ? x.symbol() : y.symbol(), x.quantity().add(y.quantity())));
        }
        return lines.isEmpty() ? null : new Statement(date, lines);
    }

    /**
     * Which holdings are which statement security. A holding with an ISIN is that security; one
     * without is matched by symbol, then by company name, and only when exactly one security fits.
     */
    static Map<String, List<Holding>> match(Map<String, Line> lines, List<Holding> holdings) {
        Map<String, List<String>> bySymbol = new HashMap<>(), byName = new HashMap<>();
        for (Line l : lines.values()) {
            String sym = StatementPositions.bareSymbol(l.symbol());
            if (sym != null) bySymbol.computeIfAbsent(sym, k -> new ArrayList<>()).add(l.isin());
            String name = StatementPositions.companyKey(l.name());
            if (name != null) byName.computeIfAbsent(name, k -> new ArrayList<>()).add(l.isin());
        }
        Map<String, List<Holding>> out = new LinkedHashMap<>();
        for (Holding h : holdings) {
            String isin = isinFor(h, bySymbol, byName);
            if (isin != null) out.computeIfAbsent(isin, k -> new ArrayList<>()).add(h);
        }
        return out;
    }

    /** The ISIN a holding is, or null when it can't be told. */
    static String isinFor(Holding h, Map<String, List<String>> bySymbol, Map<String, List<String>> byName) {
        if (h.getIsin() != null && !h.getIsin().isBlank()) return h.getIsin().trim().toUpperCase();
        List<String> s = bySymbol.get(StatementPositions.bareSymbol(h.getSymbol()));
        if (s != null && s.size() == 1) return s.get(0);
        List<String> n = byName.get(StatementPositions.companyKey(h.getName()));
        if (n != null && n.size() == 1) return n.get(0);
        return null;
    }
}
