package com.marketai.reconciliation.check;

import com.marketai.gmail.entity.DepositoryMovement;
import com.marketai.gmail.repository.DepositoryMovementRepository;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Each movement in the depository's "Transactions In Your Demat Account" notices, checked against
 * the trades on record. A share credited to the demat account was bought (or allotted as a
 * bonus) a few days before; one debited was sold. A movement no booked trade explains is a
 * contract note that never arrived or was misread — the holding is wrong from that day on.
 *
 * <p>Movements of one security on one day are added up, since the depository credits a day's
 * fills together. Trades from five days before to a day after count, which covers settlement and
 * a debit taken on the trade day itself. Only too little booked is reported: more booked than
 * moved is the duplicate the duplicate checks already find, or a fill credited on the next day.
 */
@Component
@RequiredArgsConstructor
public class DepositoryMovementCheck implements ReconciliationCheck {

    static final int DAYS_BEFORE = 5, DAYS_AFTER = 1;
    static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

    private final DepositoryMovementRepository movementRepository;
    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;

    @Override public String id() { return "DEPOSITORY_MOVEMENT_UNBOOKED"; }
    @Override public String domain() { return "PORTFOLIO"; }
    @Override public String description() {
        return "Shares the depository says moved into or out of your demat account with no trade on record to explain it";
    }

    /** One security's movements in one direction on one day. */
    record Day(String isin, String name, boolean credit, LocalDate date, BigDecimal quantity, Long firstId) {}

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        List<DepositoryMovement> movements = movementRepository.findByUserIdOrderByMovedAtAsc(userId);
        if (movements.isEmpty()) return List.of();
        // The trade's contract note may not be in yet for a movement from the last day.
        LocalDateTime settled = LocalDateTime.now().minusDays(1);
        List<Day> days = days(movements.stream().filter(m -> m.getMovedAt().isBefore(settled)).toList());
        if (days.isEmpty()) return List.of();

        List<Holding> holdings = new ArrayList<>();
        // A portfolio reset to its broker's holdings statement already has everything up to the
        // statement date in its quantities; those trades were replaced by one opening lot.
        Map<Long, LocalDate> sharesAsOf = new HashMap<>();
        LocalDate latestAsOf = null;
        for (Portfolio p : portfolioRepository.findByUserId(userId)) {
            for (Holding h : holdingRepository.findByPortfolioId(p.getId())) if (!StatementPositions.isFund(h)) holdings.add(h);
            if (p.getSharesAsOf() != null) {
                sharesAsOf.put(p.getId(), p.getSharesAsOf());
                if (latestAsOf == null || p.getSharesAsOf().isAfter(latestAsOf)) latestAsOf = p.getSharesAsOf();
            }
        }
        Map<String, List<Holding>> bySecurity = bySecurity(days, holdings);
        Map<Long, List<Transaction>> txns = holdings.isEmpty() ? Map.of() : transactionRepository
            .findByHoldingIdInOrderByTransactionDateAscIdAsc(holdings.stream().map(Holding::getId).toList())
            .stream().collect(Collectors.groupingBy(t -> t.getHolding().getId()));

        List<ReconciliationIssue> issues = new ArrayList<>();
        for (Day d : days) {
            List<Holding> matched = bySecurity.getOrDefault(d.isin(), List.of());
            if (reflectedInStatement(d, matched, sharesAsOf, latestAsOf)) continue;
            BigDecimal booked = BigDecimal.ZERO;
            for (Holding h : matched) {
                for (Transaction t : txns.getOrDefault(h.getId(), List.of())) {
                    if (!explains(t, d)) continue;
                    booked = booked.add(t.getQuantity() != null ? t.getQuantity() : BigDecimal.ZERO);
                }
            }
            if (booked.add(TOLERANCE).compareTo(d.quantity()) >= 0) continue;
            String name = matched.isEmpty() ? d.name() : Optional.ofNullable(matched.get(0).getName()).orElse(matched.get(0).getSymbol());
            String moved = d.credit() ? "credited to" : "debited from";
            String trade = d.credit() ? "purchase" : "sale";
            boolean none = booked.signum() == 0;
            issues.add(ReconciliationIssue.builder()
                .domain(domain()).type(none ? id() : "DEPOSITORY_MOVEMENT_PART_BOOKED").severity(none ? "HIGH" : "MEDIUM")
                .referenceId(d.firstId())
                .description(String.format(
                    "%s (ISIN %s): the depository says %s shares were %s your demat account on %s, but %s. %s",
                    name, d.isin(), StatementPositions.units(d.quantity()), moved, d.date(),
                    none ? "no " + trade + " in the days before is on record"
                         : "the " + trade + "s on record in the days before come to " + StatementPositions.units(booked),
                    matched.isEmpty() ? "No holding in your portfolio is this security. Import the contract note from your broker, or add the holding."
                        : "A contract note is probably missing or misread — import it from your broker. If this was not a trade "
                          + "(an IPO allotment, a corporate action, a pledge or a move between your own demat accounts), ignore it with a note."))
                .build());
        }
        return issues;
    }

    /**
     * Whether a movement is already counted in a broker holdings statement the portfolio was reset
     * to: every holding of the security is in a portfolio reset on or after the movement's date
     * — or, for a security no longer held, some portfolio was.
     */
    static boolean reflectedInStatement(Day d, List<Holding> matched, Map<Long, LocalDate> sharesAsOf, LocalDate latestAsOf) {
        if (matched.isEmpty()) return latestAsOf != null && !d.date().isAfter(latestAsOf);
        for (Holding h : matched) {
            LocalDate asOf = h.getPortfolio() == null ? null : sharesAsOf.get(h.getPortfolio().getId());
            if (asOf == null || d.date().isAfter(asOf)) return false;
        }
        return true;
    }

    /** A trade that could be this movement: the right side, dated within the settlement window. */
    static boolean explains(Transaction t, Day d) {
        if (t.getType() == null || t.getTransactionDate() == null) return false;
        boolean side = d.credit()
            ? t.getType() == Transaction.TransactionType.BUY || t.getType() == Transaction.TransactionType.BONUS
            : t.getType() == Transaction.TransactionType.SELL;
        return side && !t.getTransactionDate().isBefore(d.date().minusDays(DAYS_BEFORE))
            && !t.getTransactionDate().isAfter(d.date().plusDays(DAYS_AFTER));
    }

    /** Movements added up per security, direction and day; a row the depository sent twice counts once. */
    static List<Day> days(List<DepositoryMovement> movements) {
        Map<List<Object>, DepositoryMovement> distinct = new LinkedHashMap<>();
        for (DepositoryMovement m : movements) {
            distinct.putIfAbsent(List.of(String.valueOf(m.getAccountSuffix()), m.getIsin(), m.isCredit(),
                m.getQuantity().stripTrailingZeros(), m.getMovedAt()), m);
        }
        Map<List<Object>, Day> out = new LinkedHashMap<>();
        for (DepositoryMovement m : distinct.values()) {
            LocalDate date = m.getMovedAt().toLocalDate();
            out.merge(List.of(m.getIsin(), m.isCredit(), date),
                new Day(m.getIsin(), m.getCompanyName(), m.isCredit(), date, m.getQuantity(), m.getId()),
                (a, b) -> new Day(a.isin(), a.name(), a.credit(), a.date(), a.quantity().add(b.quantity()), a.firstId()));
        }
        return new ArrayList<>(out.values());
    }

    /** The holdings that are each moved security: by ISIN, else by company name when only one security has it. */
    static Map<String, List<Holding>> bySecurity(List<Day> days, List<Holding> holdings) {
        Map<String, List<String>> byName = new HashMap<>();
        days.stream().collect(Collectors.toMap(Day::isin, Day::name, (a, b) -> a))
            .forEach((isin, name) -> {
                String k = StatementPositions.companyKey(name);
                if (k != null) byName.computeIfAbsent(k, x -> new ArrayList<>()).add(isin);
            });
        Map<String, List<Holding>> out = new HashMap<>();
        for (Holding h : holdings) {
            String isin = h.getIsin() != null && !h.getIsin().isBlank() ? h.getIsin().trim().toUpperCase(Locale.ROOT) : null;
            if (isin == null) {
                List<String> n = byName.get(StatementPositions.companyKey(h.getName()));
                if (n != null && n.size() == 1) isin = n.get(0);
            }
            if (isin != null) out.computeIfAbsent(isin, k -> new ArrayList<>()).add(h);
        }
        return out;
    }
}
