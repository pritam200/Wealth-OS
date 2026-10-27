package com.marketai.reconciliation.service;

import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.reconciliation.entity.DataBackup;
import com.marketai.reconciliation.repository.DataBackupRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Resets one broker portfolio's shares to the broker's own holdings statement. Trades read from
 * contract notes can be misread, read twice or never read; the broker's statement is what it
 * holds for the user, so after a run of misreads it is the safe starting point.
 *
 * <p>For each share in the portfolio, every trade dated on or before the statement is replaced by
 * one opening lot of the statement's quantity at its average price; trades after the statement
 * stay and are replayed on top. Holdings that are one share under two names ("RELIANCE" and
 * "RELIANCE.NS") become one. A share the statement doesn't list, with no later trades, is
 * removed. Funds in the portfolio are not touched. The portfolio remembers the statement's date,
 * so a contract note for a trade it already includes is never booked again, and the trades
 * still waiting in the review queue from before it are closed for the same reason.
 *
 * <p>Same rules as {@link DataRebuildService}: a fresh, unused backup and an explicit
 * confirmation, and nothing is half-applied.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerStatementResetService {

    public static final String CONFIRMATION = "RESET";
    static final String LOT_NOTE = "Imported from %s holdings statement of %s; buy date unknown (statement date used)";

    private final PortfolioRepository portfolioRepo;
    private final HoldingRepository holdingRepo;
    private final TransactionRepository transactionRepo;
    private final PortfolioService portfolioService;
    private final DataBackupRepository backupRepo;
    private final EmailReviewItemRepository reviewRepo;
    private final JdbcTemplate jdbc;

    @Data @Builder
    public static class Line {
        private String symbol;
        private BigDecimal appQuantity;
        private BigDecimal appInvested;
        private BigDecimal statementQuantity;
        private BigDecimal statementInvested;
        /** KEEP (already right), ADJUST, ADD, REMOVE. */
        private String action;
        /** Holdings that become this one ("RELIANCE.NS" into "RELIANCE"). */
        private List<String> merged;
        /** Trades after the statement date, kept and replayed on top. */
        private int laterTrades;
    }

    @Data @Builder
    public static class Preview {
        private Long portfolioId;
        private String portfolioName;
        private LocalDate asOf;
        private List<Line> lines;
        private BigDecimal appInvested;
        private BigDecimal statementInvested;
        private int holdingsBefore;
        private int holdingsAfter;
        private int tradesReplaced;
        private int reviewsClosed;
        private String summary;
    }

    @Transactional(readOnly = true)
    public Preview preview(Long userId, Long portfolioId, LocalDate asOf, List<BrokerHoldingsStatement.Row> rows) {
        return plan(userId, portfolioId, asOf, rows).preview();
    }

    @Transactional
    public Preview apply(Long userId, Long portfolioId, LocalDate asOf, List<BrokerHoldingsStatement.Row> rows,
                         Long backupId, String confirm) {
        if (!CONFIRMATION.equals(confirm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reset must be confirmed with \"" + CONFIRMATION + "\".");
        }
        if (backupId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Take a backup first and pass its id.");
        DataBackup backup = backupRepo.findByIdAndUserId(backupId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Backup not found"));
        if (!DataRebuildService.usable(backup)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, backup.getAppliedAt() != null
                ? "That backup was already used on " + backup.getAppliedAt() + ". Take a new one."
                : "That backup is more than " + DataRebuildService.BACKUP_VALID_HOURS + " hours old. Take a new one.");
        }
        Plan plan = plan(userId, portfolioId, asOf, rows);
        Portfolio portfolio = plan.portfolio;
        String note = String.format(LOT_NOTE, portfolio.getName(), asOf);

        int replaced = 0;
        for (Group g : plan.groups.values()) {
            Holding keeper = g.keeper();
            for (Holding h : g.holdings) {
                for (Transaction t : transactionRepo.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId())) {
                    if (t.getTransactionDate() == null || !t.getTransactionDate().isAfter(asOf)) {
                        transactionRepo.delete(t);
                        replaced++;
                    } else if (h != keeper) {
                        t.setHolding(keeper);   // a later trade of the same share joins the one holding
                        transactionRepo.save(t);
                    }
                }
            }
            transactionRepo.flush();
            for (Holding h : g.holdings) {
                if (h != keeper) removeHolding(portfolio, h);
            }
            BrokerHoldingsStatement.Row row = plan.statement.get(g.base);
            if (row != null && row.quantity().signum() > 0) {
                addOpeningLot(keeper, row, asOf, note, portfolio.getName());
            } else if (transactionRepo.findByHoldingIdOrderByTransactionDateAscIdAsc(keeper.getId()).isEmpty()) {
                removeHolding(portfolio, keeper);
            }
            // Otherwise it was sold out by the statement date and only its later trades remain; the
            // ledger rebuild below derives its quantity from them.
        }
        for (BrokerHoldingsStatement.Row row : plan.statement.values()) {
            if (plan.groups.containsKey(row.symbol()) || row.quantity().signum() <= 0) continue;
            Holding h = holdingRepo.save(Holding.builder().portfolio(portfolio).symbol(row.symbol()).name(row.symbol())
                .quantity(row.quantity()).averageCost(row.averagePrice()).broker(portfolio.getName()).build());
            addOpeningLot(h, row, asOf, note, portfolio.getName());
        }
        transactionRepo.flush();
        holdingRepo.flush();
        portfolioService.rebuildHoldingsFromTransactions(userId);

        portfolio.setSharesAsOf(asOf);
        portfolio.setUpdatedAt(LocalDateTime.now());
        portfolioRepo.save(portfolio);

        int closed = 0;
        for (EmailReviewItem r : plan.reviewsToClose) {
            r.setStatus(ReviewStatus.REJECTED);
            r.setResolvedAt(LocalDateTime.now());
            r.setResolutionNote("Not booked: dated on or before the " + portfolio.getName() + " holdings statement of " + asOf
                + ", whose quantities already include it.");
            reviewRepo.save(r);
            closed++;
        }

        Preview done = plan.preview();
        String summary = String.format("Reset %s shares to the holdings statement of %s: %d holding(s) before, %d after; "
                + "%d trade(s) on or before that date replaced by opening lots; %d review(s) closed. Invested ₹%s → ₹%s.",
            portfolio.getName(), asOf, done.getHoldingsBefore(), done.getHoldingsAfter(), replaced, closed,
            done.getAppInvested().toPlainString(), done.getStatementInvested().toPlainString());
        backup.setAppliedAt(LocalDateTime.now());
        backup.setAppliedSummary(summary);
        backupRepo.save(backup);
        log.info("User {}: {}", userId, summary);
        done.setTradesReplaced(replaced);
        done.setReviewsClosed(closed);
        done.setSummary(summary);
        return done;
    }

    private void removeHolding(Portfolio portfolio, Holding h) {
        portfolio.getHoldings().removeIf(x -> x.getId().equals(h.getId()));
        holdingRepo.delete(h);
    }

    private void addOpeningLot(Holding h, BrokerHoldingsStatement.Row row, LocalDate asOf, String note, String broker) {
        transactionRepo.save(Transaction.builder().holding(h).type(Transaction.TransactionType.BUY)
            .quantity(row.quantity()).price(row.averagePrice()).charges(BigDecimal.ZERO)
            .transactionDate(asOf).notes(note).build());
        if (row.previousClose() != null && row.previousClose().signum() > 0) h.setCurrentPrice(row.previousClose());
        if (h.getBroker() == null || h.getBroker().isBlank()) h.setBroker(broker);
        holdingRepo.save(h);
    }

    // ── planning ──

    /** The holdings that are one share, by the symbol without its exchange suffix. */
    record Group(String base, List<Holding> holdings) {
        /** The holding kept: the one already named as the statement names it, else the oldest. */
        Holding keeper() {
            return holdings.stream().filter(h -> h.getSymbol().equalsIgnoreCase(base)).findFirst()
                .orElse(holdings.stream().min(Comparator.comparing(Holding::getId)).orElseThrow());
        }
    }

    private record Plan(Portfolio portfolio, LocalDate asOf, Map<String, BrokerHoldingsStatement.Row> statement,
                        Map<String, Group> groups, Map<Long, List<Transaction>> trades, List<EmailReviewItem> reviewsToClose) {
        Preview preview() {
            List<Line> lines = new ArrayList<>();
            BigDecimal appTotal = BigDecimal.ZERO, stTotal = BigDecimal.ZERO;
            int after = 0;
            Set<String> symbols = new TreeSet<>(groups.keySet());
            symbols.addAll(statement.keySet());
            for (String s : symbols) {
                Group g = groups.get(s);
                BrokerHoldingsStatement.Row row = statement.get(s);
                BigDecimal q = BigDecimal.ZERO, inv = BigDecimal.ZERO;
                int later = 0;
                List<String> merged = new ArrayList<>();
                if (g != null) {
                    for (Holding h : g.holdings()) {
                        BigDecimal hq = h.getQuantity() == null ? BigDecimal.ZERO : h.getQuantity();
                        q = q.add(hq);
                        if (h.getAverageCost() != null) inv = inv.add(hq.multiply(h.getAverageCost()));
                        if (h != g.keeper()) merged.add(h.getSymbol());
                        for (Transaction t : trades.getOrDefault(h.getId(), List.of())) {
                            if (t.getTransactionDate() != null && t.getTransactionDate().isAfter(asOf)) later++;
                        }
                    }
                }
                BigDecimal sq = row == null ? BigDecimal.ZERO : row.quantity();
                BigDecimal sinv = row == null ? BigDecimal.ZERO : row.invested();
                String action = g == null ? (sq.signum() > 0 ? "ADD" : null)
                    : sq.signum() == 0 && later == 0 ? "REMOVE"
                    : merged.isEmpty() && later == 0 && q.compareTo(sq) == 0 && inv.setScale(0, RoundingMode.HALF_UP).compareTo(sinv.setScale(0, RoundingMode.HALF_UP)) == 0 ? "KEEP"
                    : "ADJUST";
                if (action == null) continue;
                if (!"REMOVE".equals(action)) after++;
                appTotal = appTotal.add(inv);
                stTotal = stTotal.add(sinv);
                lines.add(Line.builder().symbol(s).appQuantity(q).appInvested(inv.setScale(2, RoundingMode.HALF_UP))
                    .statementQuantity(sq).statementInvested(sinv).action(action).merged(merged).laterTrades(later).build());
            }
            int before = groups.values().stream().mapToInt(g -> g.holdings().size()).sum();
            return Preview.builder().portfolioId(portfolio.getId()).portfolioName(portfolio.getName()).asOf(asOf).lines(lines)
                .appInvested(appTotal.setScale(2, RoundingMode.HALF_UP)).statementInvested(stTotal.setScale(2, RoundingMode.HALF_UP))
                .holdingsBefore(before).holdingsAfter(after)
                .tradesReplaced((int) trades.values().stream().flatMap(List::stream)
                    .filter(t -> t.getTransactionDate() == null || !t.getTransactionDate().isAfter(asOf)).count())
                .reviewsClosed(reviewsToClose.size()).build();
        }
    }

    private Plan plan(Long userId, Long portfolioId, LocalDate asOf, List<BrokerHoldingsStatement.Row> rows) {
        if (asOf == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the date the statement was taken.");
        if (asOf.isAfter(LocalDate.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The statement date can't be in the future.");
        Portfolio portfolio = portfolioRepo.findByIdAndUserId(portfolioId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Portfolio not found"));
        Map<String, BrokerHoldingsStatement.Row> statement = new LinkedHashMap<>();
        for (BrokerHoldingsStatement.Row r : rows) {
            if (statement.putIfAbsent(r.symbol(), r) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, r.symbol() + " is listed twice in the statement.");
            }
        }
        Map<String, Group> groups = new TreeMap<>();
        Map<Long, List<Transaction>> trades = new HashMap<>();
        for (Holding h : holdingRepo.findByPortfolioId(portfolio.getId())) {
            if (h.getSymbol() == null || h.getSymbol().toUpperCase(Locale.ROOT).endsWith(".MF")) continue;   // funds are not touched
            groups.computeIfAbsent(base(h.getSymbol()), b -> new Group(b, new ArrayList<>())).holdings().add(h);
            trades.put(h.getId(), transactionRepo.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId()));
        }
        return new Plan(portfolio, asOf, statement, groups, trades, reviewsToClose(userId, portfolio, asOf));
    }

    /**
     * Share trades waiting in the review queue from the broker this portfolio is for, dated on
     * or before the statement. The broker is told by the senders of the emails whose trades
     * were booked into this portfolio.
     */
    private List<EmailReviewItem> reviewsToClose(Long userId, Portfolio portfolio, LocalDate asOf) {
        List<String> senders = jdbc.queryForList("""
            SELECT DISTINCT lower(pe.sender) FROM transactions t
            JOIN holdings h ON h.id = t.holding_id
            JOIN processed_emails pe ON pe.gmail_message_id = t.source_email_id AND pe.user_id = ?
            WHERE h.portfolio_id = ? AND pe.sender IS NOT NULL""", String.class, userId, portfolio.getId());
        Set<String> domains = new HashSet<>();
        for (String s : senders) {
            String d = domain(s);
            if (d != null) domains.add(d);
        }
        if (domains.isEmpty()) return List.of();
        List<EmailReviewItem> out = new ArrayList<>();
        for (EmailReviewItem r : reviewRepo.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ReviewStatus.PENDING)) {
            if (r.getTransactionDate() == null || r.getTransactionDate().isAfter(asOf)) continue;
            if (!"STOCK_BUY".equals(r.getProposedType()) && !"STOCK_SELL".equals(r.getProposedType())) continue;
            String d = domain(r.getSender());
            if (d != null && domains.contains(d)) out.add(r);
        }
        return out;
    }

    static String domain(String sender) {
        if (sender == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("@([A-Za-z0-9.-]+)").matcher(sender);
        if (!m.find()) return null;
        String[] parts = m.group(1).toLowerCase(Locale.ROOT).split("\\.");
        // The registrable part: "mailer.mstock.com" and "mstock.com" are one sender.
        int n = parts.length;
        if (n >= 3 && (parts[n - 2].equals("co") || parts[n - 2].equals("com")) && parts[n - 1].length() == 2) return String.join(".", Arrays.copyOfRange(parts, n - 3, n));
        return n >= 2 ? parts[n - 2] + "." + parts[n - 1] : m.group(1).toLowerCase(Locale.ROOT);
    }

    static String base(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT).replaceAll("\\.(NS|BO)$", "");
    }
}
