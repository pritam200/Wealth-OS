package com.marketai.portfolio.service;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.common.exception.ResourceNotFoundException;
import com.marketai.market.dto.QuoteDto;
import com.marketai.market.service.MarketDataService;
import com.marketai.portfolio.dto.AddHoldingRequest;
import com.marketai.portfolio.dto.PortfolioSummaryDto;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.redemption.service.RedemptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioService {

    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final MarketDataService marketDataService;
    private final RedemptionService redemptionService;
    private final com.marketai.mf.service.MfNavHistoryService mfNavHistoryService;

    @Transactional
    public Portfolio createPortfolio(Long userId, String name, String description) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Portfolio portfolio = Portfolio.builder()
                .user(user)
                .name(name)
                .description(description)
                .build();

        return portfolioRepository.save(portfolio);
    }

    public List<Portfolio> getUserPortfolios(Long userId) {
        return portfolioRepository.findByUserIdOrderByIdAsc(userId);
    }

    public Long findHoldingId(Long portfolioId, String symbol) {
        if (portfolioId == null || symbol == null) return null;
        return holdingRepository.findByPortfolioIdAndSymbol(portfolioId, symbol.toUpperCase())
            .map(Holding::getId).orElse(null);
    }

    public boolean isDuplicateTrade(Long portfolioId, String symbol, LocalDate date, BigDecimal quantity, BigDecimal price) {
        if (portfolioId == null || symbol == null || date == null || quantity == null || price == null) return false;
        Optional<Holding> holding = holdingRepository.findByPortfolioIdAndSymbol(portfolioId, symbol.toUpperCase());
        if (!holding.isPresent()) return false;
        return transactionRepository.existsByHoldingIdAndTransactionDateAndQuantityAndPrice(
            holding.get().getId(), date, quantity, price);
    }

    /**
     * Same check, using the broker's trade number when the source states one. Two fills of the
     * same size at the same price on one day are two trades if their trade numbers differ —
     * the date/quantity/price check alone merged them into one. A recorded trade with no
     * number (older imports, hand entries) still counts as the same trade.
     */
    public boolean isDuplicateTrade(Long portfolioId, String symbol, LocalDate date, BigDecimal quantity,
                                    BigDecimal price, String tradeReference) {
        if (tradeReference == null || tradeReference.isBlank()) {
            return isDuplicateTrade(portfolioId, symbol, date, quantity, price);
        }
        if (portfolioId == null || symbol == null || date == null || quantity == null || price == null) return false;
        Optional<Holding> holding = holdingRepository.findByPortfolioIdAndSymbol(portfolioId, symbol.toUpperCase());
        if (holding.isEmpty()) return false;
        String ref = tradeReference.trim();
        for (Transaction t : transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(holding.get().getId())) {
            String theirs = t.getProvenance() != null ? t.getProvenance().getSourceReference() : null;
            if (theirs != null && theirs.equalsIgnoreCase(ref)) return true;
            boolean sameFigures = date.equals(t.getTransactionDate()) && t.getQuantity() != null
                && t.getQuantity().compareTo(quantity) == 0 && t.getPrice() != null && t.getPrice().compareTo(price) == 0;
            if (sameFigures && theirs == null) return true;
        }
        return false;
    }

    /** What a corporate-action booking did — or why it could not be booked. */
    public record CorporateActionResult(boolean booked, boolean duplicate, String detail) {
        static CorporateActionResult ok(String d) { return new CorporateActionResult(true, false, d); }
        static CorporateActionResult dup(String d) { return new CorporateActionResult(false, true, d); }
    }

    /**
     * A split: every share held on the record date becomes {@code to/from} shares. Lots keep
     * their purchase dates and total cost; the price per share divides. Booked once per holding
     * per date.
     *
     * @throws IllegalArgumentException when the stock is not held or the ratio is unusable
     */
    @Transactional
    public CorporateActionResult recordSplit(Long portfolioId, String symbol, BigDecimal from, BigDecimal to,
                                             LocalDate recordDate, com.marketai.common.ledger.Provenance provenance) {
        Holding h = requireHeld(portfolioId, symbol, "split");
        requireRatio(from, to, "split");
        if (hasAction(h, Transaction.TransactionType.SPLIT, recordDate)) {
            return CorporateActionResult.dup("The " + from.stripTrailingZeros().toPlainString() + ":"
                + to.stripTrailingZeros().toPlainString() + " split of " + h.getSymbol() + " on " + recordDate + " is already recorded.");
        }
        transactionRepository.save(Transaction.builder().holding(h).type(Transaction.TransactionType.SPLIT)
            .quantity(BigDecimal.ZERO).price(BigDecimal.ZERO).ratioFrom(from).ratioTo(to)
            .transactionDate(recordDate).notes("Split " + from.stripTrailingZeros().toPlainString() + ":" + to.stripTrailingZeros().toPlainString())
            .provenance(provenance).build());
        recomputeFromLedger(h);
        return CorporateActionResult.ok("Split " + from.stripTrailingZeros().toPlainString() + ":"
            + to.stripTrailingZeros().toPlainString() + " recorded for " + h.getSymbol());
    }

    /**
     * Bonus shares: {@code to} new shares for every {@code from} held on the record date, or
     * the number stated as credited. They cost nil and are acquired on the allotment date,
     * which is how they are taxed. Fractional entitlements are paid in cash, not shares, so the
     * computed number is rounded down.
     */
    @Transactional
    public CorporateActionResult recordBonus(Long portfolioId, String symbol, BigDecimal from, BigDecimal to,
                                             BigDecimal statedUnits, LocalDate allotmentDate,
                                             com.marketai.common.ledger.Provenance provenance) {
        Holding h = requireHeld(portfolioId, symbol, "bonus issue");
        if (hasAction(h, Transaction.TransactionType.BONUS, allotmentDate)) {
            return CorporateActionResult.dup("The bonus issue of " + h.getSymbol() + " on " + allotmentDate + " is already recorded.");
        }
        BigDecimal units = statedUnits;
        if (units == null || units.signum() <= 0) {
            requireRatio(from, to, "bonus issue");
            BigDecimal held = unitsHeldOn(h, allotmentDate);
            units = held.multiply(to).divide(from, 0, RoundingMode.FLOOR);
        }
        if (units.signum() <= 0) {
            throw new IllegalArgumentException("No bonus shares of " + h.getSymbol() + " are due on " + allotmentDate
                + " — nothing was held on that date according to the recorded trades.");
        }
        transactionRepository.save(Transaction.builder().holding(h).type(Transaction.TransactionType.BONUS)
            .quantity(units).price(BigDecimal.ZERO).ratioFrom(from).ratioTo(to)
            .transactionDate(allotmentDate).notes("Bonus shares").provenance(provenance).build());
        recomputeFromLedger(h);
        return CorporateActionResult.ok(units.stripTrailingZeros().toPlainString() + " bonus share(s) of " + h.getSymbol() + " recorded");
    }

    /**
     * An amalgamation: shares of the merged company are exchanged for shares of the surviving
     * one at a ratio, tax-neutrally — the new shares keep the old purchase dates and cost. Booked
     * as a split by the exchange ratio plus a change of symbol. When the surviving company is
     * already held, the two positions' lots would have to be combined, which is left to the user
     * rather than done by rewriting trades.
     */
    @Transactional
    public CorporateActionResult recordMerger(Long portfolioId, String symbol, String newSymbol, BigDecimal from,
                                              BigDecimal to, LocalDate effectiveDate,
                                              com.marketai.common.ledger.Provenance provenance) {
        Holding h = requireHeld(portfolioId, symbol, "merger");
        requireRatio(from, to, "merger");
        if (newSymbol == null || newSymbol.isBlank()) {
            throw new IllegalArgumentException("The merger of " + h.getSymbol() + " does not name the company whose shares replace it.");
        }
        String target = newSymbol.toUpperCase();
        if (holdingRepository.findByPortfolioIdAndSymbol(portfolioId, target).isPresent()) {
            throw new IllegalArgumentException(h.getSymbol() + " was merged into " + target + ", which you already hold. "
                + "Combine the two positions by hand so each purchase keeps its own date and cost.");
        }
        if (hasAction(h, Transaction.TransactionType.SPLIT, effectiveDate)) {
            return CorporateActionResult.dup("The merger of " + h.getSymbol() + " on " + effectiveDate + " is already recorded.");
        }
        String old = h.getSymbol();
        transactionRepository.save(Transaction.builder().holding(h).type(Transaction.TransactionType.SPLIT)
            .quantity(BigDecimal.ZERO).price(BigDecimal.ZERO).ratioFrom(from).ratioTo(to)
            .transactionDate(effectiveDate).notes("Merged from " + old + " into " + target)
            .provenance(provenance).build());
        h.setSymbol(target);
        h.setName(target.replace(".NS", "").replace(".BO", ""));
        h.setIsin(null);                 // the old company's ISIN no longer identifies this position
        h.setCurrentPrice(null);         // the old company's price is not the new one's
        h.setPriceAsOf(null);
        h.setUpdatedAt(LocalDateTime.now());
        holdingRepository.save(h);
        recomputeFromLedger(h);
        return CorporateActionResult.ok(old + " merged into " + target + " at " + from.stripTrailingZeros().toPlainString()
            + ":" + to.stripTrailingZeros().toPlainString());
    }

    private Holding requireHeld(Long portfolioId, String symbol, String what) {
        if (symbol == null) throw new IllegalArgumentException("The " + what + " does not name a stock.");
        return holdingRepository.findByPortfolioIdAndSymbol(portfolioId, symbol.toUpperCase())
            .orElseThrow(() -> new IllegalArgumentException("A " + what + " of " + symbol
                + " was found, but " + symbol + " is not held — import its purchases first."));
    }

    private static void requireRatio(BigDecimal from, BigDecimal to, String what) {
        if (from == null || to == null || from.signum() <= 0 || to.signum() <= 0) {
            throw new IllegalArgumentException("The " + what + " ratio could not be read.");
        }
    }

    private boolean hasAction(Holding h, Transaction.TransactionType type, LocalDate date) {
        return transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId()).stream()
            .anyMatch(t -> t.getType() == type && date != null && date.equals(t.getTransactionDate()));
    }

    /** Units held at the end of {@code date} by the recorded trades. */
    private BigDecimal unitsHeldOn(Holding h, LocalDate date) {
        BigDecimal qty = BigDecimal.ZERO;
        for (Transaction t : transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId())) {
            if (t.getTransactionDate() == null || t.getTransactionDate().isAfter(date)) continue;
            if (t.getType() == Transaction.TransactionType.SPLIT) {
                BigDecimal m = t.splitMultiplier();
                if (m != null) qty = qty.multiply(m);
            } else if (t.addsUnits()) {
                qty = qty.add(t.getQuantity());
            } else {
                qty = qty.subtract(t.getQuantity()).max(BigDecimal.ZERO);
            }
        }
        return qty;
    }

    /**
     * The symbol a fund transaction belongs under, given the name-derived symbol and the ISIN
     * (if the source stated one):
     * <ul>
     *   <li>a holding already carrying this ISIN — its symbol, whatever it is called;</li>
     *   <li>otherwise the name-derived symbol, unless a holding under it carries a
     *       <i>different</i> ISIN — then it is another plan or option of a similarly named
     *       fund, and this one gets its own symbol, suffixed with its ISIN.</li>
     * </ul>
     * Without an ISIN the name-derived symbol is used as before.
     */
    @Transactional(readOnly = true)
    public String resolveFundSymbol(Long portfolioId, String nameSymbol, String isin) {
        if (isin == null || isin.isBlank()) return nameSymbol;
        String id = isin.trim().toUpperCase();
        List<Holding> byIsin = holdingRepository.findByPortfolioIdAndIsinIgnoreCase(portfolioId, id);
        if (!byIsin.isEmpty()) return byIsin.get(0).getSymbol();
        Optional<Holding> byName = holdingRepository.findByPortfolioIdAndSymbol(portfolioId, nameSymbol.toUpperCase());
        if (byName.isPresent() && byName.get().getIsin() != null && !byName.get().getIsin().isBlank()
                && !byName.get().getIsin().trim().equalsIgnoreCase(id)) {
            String base = nameSymbol.toUpperCase().endsWith(".MF")
                ? nameSymbol.substring(0, nameSymbol.length() - 3) : nameSymbol;
            return base + "-" + id + ".MF";
        }
        return nameSymbol;
    }

    @Transactional
    public Holding addHolding(Long portfolioId, Long userId, AddHoldingRequest req) {
        Portfolio portfolio = portfolioRepository.findByIdAndUserId(portfolioId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));

        Optional<Holding> existing = holdingRepository
                .findByPortfolioIdAndSymbol(portfolioId, req.getSymbol().toUpperCase());
        // A fund's symbol is derived from its name, and two statements spell a name differently.
        // The ISIN is the identity: the same ISIN is the same holding whatever it is called.
        if (existing.isPresent() && req.getIsin() != null && !req.getIsin().isBlank()
                && existing.get().getIsin() != null && !existing.get().getIsin().isBlank()
                && !existing.get().getIsin().trim().equalsIgnoreCase(req.getIsin().trim())) {
            // Same symbol, different security — never pool two funds' units.
            throw new IllegalArgumentException("A holding named " + req.getSymbol() + " already exists for ISIN "
                + existing.get().getIsin() + ", but this purchase is for ISIN " + req.getIsin() + ".");
        }
        if (existing.isEmpty() && req.getIsin() != null && !req.getIsin().isBlank()) {
            existing = holdingRepository.findByPortfolioIdAndIsinIgnoreCase(portfolioId, req.getIsin().trim())
                    .stream().findFirst();
        }

        Holding holding;
        if (existing.isPresent()) {
            holding = existing.get();
            // Fill broker/folio only if not already set
            if (holding.getBroker() == null && req.getBroker() != null) {
                holding.setBroker(req.getBroker());
            }
            if (holding.getFolio() == null && req.getFolio() != null) {
                holding.setFolio(req.getFolio());
            }
            if (holding.getIsin() == null && req.getIsin() != null) {
                holding.setIsin(req.getIsin());
            }
            if (holding.getDpId() == null && req.getDpId() != null) {
                holding.setDpId(req.getDpId());
            }
            if (holding.getClientId() == null && req.getClientId() != null) {
                holding.setClientId(req.getClientId());
            }
            // Update buyDate if the new transaction is earlier
            if (req.getTransactionDate() != null) {
                if (holding.getBuyDate() == null || req.getTransactionDate().isBefore(holding.getBuyDate())) {
                    holding.setBuyDate(req.getTransactionDate());
                }
            }
        } else {
            holding = Holding.builder()
                    .portfolio(portfolio)
                    .symbol(req.getSymbol().toUpperCase())
                    .name(req.getName())
                    .quantity(req.getQuantity())
                    .averageCost(req.getPrice())
                    .build();
            if (req.getBroker() != null) {
                holding.setBroker(req.getBroker());
            }
            if (req.getFolio() != null) {
                holding.setFolio(req.getFolio());
            }
            if (req.getIsin() != null) {
                holding.setIsin(req.getIsin());
            }
            if (req.getDpId() != null) {
                holding.setDpId(req.getDpId());
            }
            if (req.getClientId() != null) {
                holding.setClientId(req.getClientId());
            }
            holding.setBuyDate(req.getTransactionDate());
        }

        holding = holdingRepository.save(holding);

        Transaction txn = Transaction.builder()
                .holding(holding)
                .type(Transaction.TransactionType.BUY)
                .quantity(req.getQuantity())
                .price(req.getPrice())
                .charges(req.getCharges())
                .transactionDate(req.getTransactionDate())
                .notes(req.getNotes())
                .provenance(req.getProvenance() != null ? req.getProvenance() : com.marketai.common.ledger.Provenance.manual())
                .build();
        transactionRepository.save(txn);

        // A BUY only ever increases net quantity, so this never returns null (never deletes).
        return recomputeFromLedger(holding);
    }

    /**
     * Refreshes every priceable holding and reports how many actually got a price.
     *
     * <p>It used to return nothing, and the endpoint answered {@code {"status":"recalculated"}}
     * with 200 even when every single quote failed — telling the user their prices were up to
     * date when none of them had moved. The per-symbol failure is also logged at warn now, not
     * debug: a quote provider that has started refusing every request should be visible.
     */
    @Transactional
    public RefreshOutcome refreshAllPrices(Long userId) {
        List<Portfolio> portfolios = portfolioRepository.findByUserId(userId);
        int attempted = 0, updated = 0;
        for (Portfolio portfolio : portfolios) {
            List<Holding> holdings = holdingRepository.findOpenByPortfolioId(portfolio.getId());
            for (Holding h : holdings) {
                String sym = h.getSymbol();
                if (sym == null) continue;
                if (sym.endsWith(".MF")) {
                    // No live quote feed for MFs — re-apply the latest stored AMFI NAV instead
                    // of skipping outright, so "Refresh" isn't a no-op for mutual funds.
                    String schemeCode = h.getAmfiSchemeCode();
                    if (schemeCode == null) continue;
                    attempted++;
                    try {
                        if (mfNavHistoryService.syncHoldingValuations(schemeCode) > 0) updated++;
                    } catch (Exception e) {
                        log.warn("NAV refresh failed for {}: {}", sym, e.getMessage());
                    }
                    continue;
                }
                attempted++;
                try {
                    QuoteDto quote = marketDataService.getQuote(sym);
                    if (quote != null && quote.getCurrentPrice() != null
                            && quote.getCurrentPrice().signum() > 0) {
                        h.applyPrice(quote.getCurrentPrice(), quoteDate(quote));
                        h.setUpdatedAt(LocalDateTime.now());
                        holdingRepository.save(h);
                        updated++;
                    }
                } catch (Exception e) {
                    log.warn("Price refresh failed for {}: {}", sym, e.getMessage());
                }
            }
        }
        if (attempted > 0 && updated == 0) {
            log.warn("Price refresh for user {}: none of {} symbol(s) returned a usable quote", userId, attempted);
        }
        return new RefreshOutcome(attempted, updated);
    }

    /** The day a quote is for; a quote without a timestamp is taken as today's. */
    private static LocalDate quoteDate(QuoteDto quote) {
        return quote.getLastUpdated() != null ? quote.getLastUpdated().toLocalDate() : LocalDate.now();
    }

    /** @param attempted priceable (non-MF) holdings tried; @param updated those that got a price */
    public record RefreshOutcome(int attempted, int updated) {
        public boolean completelyFailed() { return attempted > 0 && updated == 0; }
    }

    /**
     * The work list for the nightly reconciliation pass — every user whose holdings should be
     * replayed against their transaction ledger.
     *
     * <p>The per-user loop deliberately lives in {@code HoldingReconciliationScheduler} rather than
     * here. When this class looped and called {@link #rebuildHoldingsFromTransactions} on
     * {@code this}, the call never crossed the Spring proxy, so that method's
     * {@code @Transactional} had no effect and the whole sweep ran on auto-commit: a failure
     * partway through left a portfolio half-rebuilt, with an already-committed delete of a
     * fully-sold holding and nothing to roll back. Iterating from the scheduler means each user's
     * rebuild is a real transaction.
     */
    public List<Long> allUserIdsForReconciliation() {
        return userRepository.findAll().stream().map(User::getId).toList();
    }

    /**
     * Re-derives every holding's units and average cost from its ledger. Derived figures only:
     * no transaction is touched, and a holding already matching its ledger is not rewritten.
     * Holdings with no ledger rows at all are left as they are — their figures are the only
     * record there is.
     *
     * @return how many holdings changed
     */
    @Transactional
    public int rebuildHoldingsFromTransactions(Long userId) {
        int fixed = 0;
        for (Portfolio portfolio : portfolioRepository.findByUserId(userId)) {
            // Loaded once per holding and passed down, so the replay does not re-query them.
            for (Holding h : holdingRepository.findByPortfolioId(portfolio.getId())) {
                List<Transaction> txns = transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId());
                if (txns.isEmpty()) continue;
                LedgerPosition pos = replayPosition(txns);
                if (sameFigure(h.getQuantity(), pos.quantity()) && sameFigure(h.getAverageCost(), pos.averageCost())) continue;
                recomputeFromLedger(h, txns);
                fixed++;
                log.info("Rebuilt holding {} from its ledger: qty={}, avgCost={}", h.getSymbol(), pos.quantity(), pos.averageCost());
            }
        }
        return fixed;
    }

    /**
     * Real XIRR from this holding's actual BUY/SELL cash flows plus its current value as a
     * final "as-if-sold-today" flow — replaces trusting {@link Holding#getXirr()}, a plain
     * stored field populated only by manual entry or import, never verified against what the
     * ledger implies. Falls back to that stored value only when the ledger itself can't
     * produce a real answer (e.g. a holding with no transaction history at all, from an old
     * bulk import) — a manual/unverified number is still better than blank.
     */
    private BigDecimal computeRealXirr(Holding h, List<Transaction> txns) {
        return com.marketai.portfolio.util.XirrCalculator.holdingXirr(h, txns);
    }

    /**
     * The single place quantity/averageCost is ever derived — always by replaying the full
     * BUY/SELL transaction ledger for this holding, never by hand-rolling weighted-average
     * math inline (that pattern used to be duplicated across addHolding/sellHolding/merge,
     * and could silently drift from what the ledger actually implies). Returns null if the ledger nets to zero or negative quantity (fully sold), leaving the
     * holding closed at zero units with its history intact.
     */
    private Holding recomputeFromLedger(Holding h) {
        return recomputeFromLedger(h, transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId()));
    }

    /** What the ledger says a position is: units held and their weighted-average cost. */
    public record LedgerPosition(BigDecimal quantity, BigDecimal averageCost) {}

    /** The replay itself, with no side effects — the data audit compares stored holdings to it. */
    public static LedgerPosition replayPosition(List<Transaction> txns) {
        BigDecimal netQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (Transaction t : txns) {
            if (t.getType() == Transaction.TransactionType.SPLIT) {
                // More shares, the same total cost.
                BigDecimal m = t.splitMultiplier();
                if (m != null) netQty = netQty.multiply(m);
                continue;
            }
            if (t.getQuantity() == null || t.getPrice() == null) continue;
            if (t.addsUnits()) {
                // A bonus adds shares at nil cost, diluting the average.
                totalCost = totalCost.add(t.getPrice().multiply(t.getQuantity()));
                netQty = netQty.add(t.getQuantity());
            } else {
                BigDecimal sellQty = t.getQuantity().min(netQty);
                if (netQty.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal avgCostAtSale = totalCost.divide(netQty, 4, RoundingMode.HALF_UP);
                    totalCost = totalCost.subtract(avgCostAtSale.multiply(sellQty));
                }
                netQty = netQty.subtract(sellQty);
                if (netQty.compareTo(BigDecimal.ZERO) < 0) netQty = BigDecimal.ZERO;
                if (totalCost.compareTo(BigDecimal.ZERO) < 0) totalCost = BigDecimal.ZERO;
            }
        }
        if (netQty.compareTo(BigDecimal.ZERO) <= 0) return new LedgerPosition(BigDecimal.ZERO, BigDecimal.ZERO);
        return new LedgerPosition(netQty, totalCost.divide(netQty, 4, RoundingMode.HALF_UP));
    }

    /** Same replay against transactions the caller already holds, to avoid re-querying them. */
    private Holding recomputeFromLedger(Holding h, List<Transaction> txns) {
        LedgerPosition pos = replayPosition(txns);
        if (pos.quantity().signum() <= 0) {
            // Fully sold: the position is closed, not erased. Deleting the holding used to
            // cascade to its whole BUY/SELL history, taking the realized-gain and tax record
            // with it. A closed holding stays at zero units and is left out of open-position
            // reads (HoldingRepository.findOpenByPortfolioId); a later buy reopens it.
            h.setQuantity(BigDecimal.ZERO);
            h.setAverageCost(BigDecimal.ZERO);
            h.setUpdatedAt(LocalDateTime.now());
            holdingRepository.save(h);
            return null;
        }
        h.setQuantity(pos.quantity());
        h.setAverageCost(pos.averageCost());
        h.setUpdatedAt(LocalDateTime.now());
        return holdingRepository.save(h);
    }

    /**
     * The first sale, in date order, that sells more units than were held at that point, or null.
     * A stable sort keeps same-day rows in ledger order, so a same-day buy recorded first covers
     * the sale.
     */
    static String firstOversell(List<Transaction> txns) {
        BigDecimal held = BigDecimal.ZERO;
        for (Transaction t : txns) {
            if (t.getType() == Transaction.TransactionType.SPLIT) {
                BigDecimal m = t.splitMultiplier();
                if (m != null) held = held.multiply(m);
                continue;
            }
            if (t.getQuantity() == null) continue;
            if (t.addsUnits()) {
                held = held.add(t.getQuantity());
            } else if (t.getType() == Transaction.TransactionType.SELL) {
                if (t.getQuantity().subtract(held).compareTo(new BigDecimal("0.0001")) > 0) {
                    return "on " + t.getTransactionDate() + " only " + held.stripTrailingZeros().toPlainString()
                        + " units were held, but " + t.getQuantity().stripTrailingZeros().toPlainString() + " would be sold.";
                }
                held = held.subtract(t.getQuantity());
            }
        }
        return null;
    }

    public static boolean sameFigure(BigDecimal a, BigDecimal b) {
        BigDecimal x = a == null ? BigDecimal.ZERO : a;
        BigDecimal y = b == null ? BigDecimal.ZERO : b;
        return x.subtract(y).abs().compareTo(new BigDecimal("0.0001")) <= 0;
    }

    @Transactional(readOnly = true)
    public PortfolioSummaryDto getPortfolioSummary(Long portfolioId, Long userId) {
        Portfolio portfolio = portfolioRepository.findByIdAndUserId(portfolioId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));

        List<Holding> holdings = holdingRepository.findOpenByPortfolioId(portfolioId);
        return summarise(portfolioId, portfolio.getName(), holdings);
    }

    /**
     * One summary over every portfolio the user owns. A user can hold several portfolio rows
     * (each import path used to resolve "the portfolio" on its own), and the screens used to
     * fetch each summary and add them up in the browser — a second, client-side calculation of
     * the same totals. This does the aggregation once, here, with the same arithmetic.
     */
    @Transactional(readOnly = true)
    public PortfolioSummaryDto getCombinedSummary(Long userId) {
        List<Portfolio> portfolios = portfolioRepository.findByUserIdOrderByIdAsc(userId);
        List<Holding> holdings = new ArrayList<>();
        for (Portfolio p : portfolios) holdings.addAll(holdingRepository.findOpenByPortfolioId(p.getId()));
        Portfolio first = portfolios.isEmpty() ? null : portfolios.get(0);
        return summarise(first != null ? first.getId() : null, first != null ? first.getName() : null, holdings);
    }

    private PortfolioSummaryDto summarise(Long portfolioId, String portfolioName, List<Holding> holdings) {
        // Fetch live prices. Mutual funds have no Yahoo feed (always 404) and carry a
        // manually-set NAV, so skip them. Never overwrite a good price with a failed/zero quote.
        for (Holding h : holdings) {
            String sym = h.getSymbol();
            if (sym == null || sym.endsWith(".MF")) continue;   // MF NAV is stored, not live
            // If we already have a price (e.g. imported from a broker sheet), skip the slow
            // live fetch — 60+ blocking Yahoo calls per load made the summary time out, which
            // left equity showing as ₹0 in the Risk Matrix / net worth. Use "Refresh" to re-fetch.
            if (h.getCurrentPrice() != null && h.getCurrentPrice().signum() > 0) continue;
            try {
                QuoteDto quote = marketDataService.getQuote(sym);
                if (quote != null && quote.getCurrentPrice() != null
                        && quote.getCurrentPrice().signum() > 0) {
                    h.applyPrice(quote.getCurrentPrice(), quoteDate(quote));
                }
            } catch (Exception e) {
                log.debug("No live price for {} — keeping stored price", sym);
            }
        }

        BigDecimal totalInvested = holdings.stream()
                .map(Holding::getInvestedValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal currentValue = holdings.stream()
                .map(Holding::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPnl = currentValue.subtract(totalInvested);
        BigDecimal totalPnlPct = totalInvested.compareTo(BigDecimal.ZERO) != 0
                ? totalPnl.divide(totalInvested, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        // One query for every holding's transactions, grouped in memory, instead of one query per
        // holding inside the mapping below.
        Map<Long, List<Transaction>> txnsByHolding = holdings.isEmpty()
                ? Map.of()
                : transactionRepository
                    .findByHoldingIdInOrderByTransactionDateAscIdAsc(holdings.stream().map(Holding::getId).toList())
                    .stream()
                    .collect(Collectors.groupingBy(t -> t.getHolding().getId()));

        List<PortfolioSummaryDto.HoldingDto> holdingDtos = holdings.stream()
                .map(h -> {
                    BigDecimal weight = currentValue.compareTo(BigDecimal.ZERO) != 0
                            ? h.getCurrentValue().divide(currentValue, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                            : BigDecimal.ZERO;
                    return PortfolioSummaryDto.HoldingDto.builder()
                            .id(h.getId())
                            .portfolioId(h.getPortfolio() != null ? h.getPortfolio().getId() : portfolioId)
                            .symbol(h.getSymbol())
                            .name(h.getName())
                            .quantity(h.getQuantity())
                            .averageCost(h.getAverageCost())
                            .currentPrice(h.getCurrentPrice())
                            .investedValue(h.getInvestedValue())
                            .currentValue(h.getCurrentValue())
                            .pnl(h.getPnl())
                            .pnlPercent(h.getPnlPercent())
                            .weightPercent(weight)
                            .broker(h.getBroker())
                            .folio(h.getFolio())
                            .isin(h.getIsin())
                            .dpId(h.getDpId())
                            .clientId(h.getClientId())
                            .buyDate(h.getBuyDate())
                            .xirr(computeRealXirr(h, txnsByHolding.getOrDefault(h.getId(), List.of())))
                            .priceAsOf(h.getPriceAsOf())
                            .valuationBasis(h.getValuationBasis().name())
                            .build();
                })
                .collect(Collectors.toList());

        // Group by display name (not raw symbol) so the chart shows "ICICI Bank" / "Mirae
        // Asset Large Cap Fund" instead of mangled internal symbols like "ICICIBANK.NS" or
        // an auto-generated MF symbol. Also aggregates any holdings that legitimately share
        // a display name (e.g. re-imported under a slightly different symbol) into one slice.
        java.util.Map<String, BigDecimal> valueByLabel = new java.util.LinkedHashMap<>();
        for (PortfolioSummaryDto.HoldingDto h : holdingDtos) {
            String label = displayLabel(h.getName(), h.getSymbol());
            valueByLabel.merge(label, h.getCurrentValue(), BigDecimal::add);
        }
        List<PortfolioSummaryDto.AllocationDto> allocation = valueByLabel.entrySet().stream()
                .map(e -> PortfolioSummaryDto.AllocationDto.builder()
                        .label(e.getKey())
                        .value(e.getValue())
                        .percent(currentValue.compareTo(BigDecimal.ZERO) != 0
                                ? e.getValue().divide(currentValue, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                                : BigDecimal.ZERO)
                        .build())
                .sorted(Comparator.comparing(PortfolioSummaryDto.AllocationDto::getValue).reversed())
                .collect(Collectors.toList());

        return PortfolioSummaryDto.builder()
                .portfolioId(portfolioId)
                .name(portfolioName)
                .totalInvested(totalInvested)
                .currentValue(currentValue)
                .totalPnl(totalPnl)
                .totalPnlPercent(totalPnlPct)
                .stocksInvested(sumWhere(holdings, false, Holding::getInvestedValue))
                .stocksCurrentValue(sumWhere(holdings, false, Holding::getCurrentValue))
                .mfInvested(sumWhere(holdings, true, Holding::getInvestedValue))
                .mfCurrentValue(sumWhere(holdings, true, Holding::getCurrentValue))
                .holdingsAtCost((int) holdings.stream().filter(h -> h.getValuationBasis() == Holding.ValuationBasis.COST).count())
                .valueAtCost(valueWithBasis(holdings, Holding.ValuationBasis.COST))
                .holdingsStale((int) holdings.stream().filter(h -> h.getValuationBasis() == Holding.ValuationBasis.STALE).count())
                .valueStale(valueWithBasis(holdings, Holding.ValuationBasis.STALE))
                .holdings(holdingDtos)
                .allocation(allocation)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private static BigDecimal sumWhere(List<Holding> holdings, boolean mf,
                                       java.util.function.Function<Holding, BigDecimal> value) {
        return holdings.stream()
                .filter(h -> (h.getSymbol() != null && h.getSymbol().toUpperCase().endsWith(".MF")) == mf)
                .map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal valueWithBasis(List<Holding> holdings, Holding.ValuationBasis basis) {
        return holdings.stream().filter(h -> h.getValuationBasis() == basis)
                .map(Holding::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Clean, chart-friendly label: the holding's display name, or a de-suffixed symbol as a last resort. */
    private static String displayLabel(String name, String symbol) {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        if (symbol == null) return "Unknown";
        return symbol.replace(".NS", "").replace(".BO", "").replace(".MF", "");
    }

    /**
     * Read-only audit over every holding a user owns, surfacing the exact corruption patterns
     * that caused the Aug-2026 net-worth incident so they can be caught (and removed via the
     * UI) before they distort the dashboard again — instead of requiring another manual DB dive.
     */
    @Transactional(readOnly = true)
    public com.marketai.portfolio.dto.IntegrityReportDto checkIntegrity(Long userId) {
        List<Holding> all = new ArrayList<>();
        for (Portfolio p : portfolioRepository.findByUserId(userId)) {
            all.addAll(holdingRepository.findOpenByPortfolioId(p.getId()));
        }

        List<com.marketai.portfolio.dto.IntegrityReportDto.Issue> issues = new ArrayList<>();

        // 1. Header/boilerplate text mistaken for a fund name (e.g. "Name Cost of Investment").
        for (Holding h : all) {
            if (com.marketai.common.util.FinancialDataValidator.looksLikeUnverifiableFundName(h.getName())) {
                issues.add(com.marketai.portfolio.dto.IntegrityReportDto.Issue.builder()
                    .holdingId(h.getId()).symbol(h.getSymbol()).name(h.getName())
                    .type("UNVERIFIABLE_NAME")
                    .description("Name looks like mis-parsed statement header text, not a real fund/stock name")
                    .currentValue(h.getCurrentValue())
                    .build());
            }
        }

        // 2. Same folio number appearing under more than one symbol — the same real-world
        // fund got fragmented (or double-imported) into separate holdings.
        java.util.Map<String, List<Holding>> byFolio = new java.util.LinkedHashMap<>();
        for (Holding h : all) {
            if (h.getFolio() != null && !h.getFolio().trim().isEmpty()) {
                byFolio.computeIfAbsent(h.getFolio().trim(), k -> new ArrayList<>()).add(h);
            }
        }
        for (java.util.Map.Entry<String, List<Holding>> e : byFolio.entrySet()) {
            List<Holding> group = e.getValue();
            long distinctSymbols = group.stream().map(Holding::getSymbol).distinct().count();
            if (distinctSymbols > 1) {
                for (Holding h : group) {
                    issues.add(com.marketai.portfolio.dto.IntegrityReportDto.Issue.builder()
                        .holdingId(h.getId()).symbol(h.getSymbol()).name(h.getName())
                        .type("DUPLICATE_FOLIO")
                        .description("Folio " + e.getKey() + " is split across " + distinctSymbols + " different holdings — likely the same fund imported more than once")
                        .currentValue(h.getCurrentValue())
                        .build());
                }
            }
        }

        // 3. Same display name under more than one symbol (folio absent, or symbol drifted
        // between import runs due to unstable auto-generated symbol text).
        java.util.Map<String, List<Holding>> byName = new java.util.LinkedHashMap<>();
        for (Holding h : all) {
            String key = displayLabel(h.getName(), h.getSymbol()).toLowerCase();
            byName.computeIfAbsent(key, k -> new ArrayList<>()).add(h);
        }
        for (java.util.Map.Entry<String, List<Holding>> e : byName.entrySet()) {
            List<Holding> group = e.getValue();
            long distinctSymbols = group.stream().map(Holding::getSymbol).distinct().count();
            if (distinctSymbols > 1) {
                for (Holding h : group) {
                    boolean alreadyFlagged = issues.stream().anyMatch(i -> i.getHoldingId().equals(h.getId()) && "DUPLICATE_FOLIO".equals(i.getType()));
                    if (alreadyFlagged) continue;
                    issues.add(com.marketai.portfolio.dto.IntegrityReportDto.Issue.builder()
                        .holdingId(h.getId()).symbol(h.getSymbol()).name(h.getName())
                        .type("DUPLICATE_DISPLAY_NAME")
                        .description("\"" + h.getName() + "\" appears under " + distinctSymbols + " different symbols — possible duplicate import")
                        .currentValue(h.getCurrentValue())
                        .build());
                }
            }
        }

        // 4. The exact same symbol held under more than one portfolio. The (portfolio_id,
        // symbol) unique constraint only prevents a duplicate WITHIN one portfolio — it does
        // nothing when a user has accumulated several portfolio rows (see the Aug-2026
        // fragmentation bug), so the identical stock can silently exist twice, each counted
        // in full. This is the one duplicate type safe to auto-merge (mergeDuplicateSymbols
        // below): it's a literal string match, not a heuristic, so there is no risk of
        // combining two genuinely different positions.
        java.util.Map<String, List<Holding>> bySymbol = new java.util.LinkedHashMap<>();
        for (Holding h : all) {
            if (h.getSymbol() == null) continue;
            bySymbol.computeIfAbsent(h.getSymbol().toUpperCase(), k -> new ArrayList<>()).add(h);
        }
        for (java.util.Map.Entry<String, List<Holding>> e : bySymbol.entrySet()) {
            List<Holding> group = e.getValue();
            if (group.size() <= 1) continue;
            for (Holding h : group) {
                issues.add(com.marketai.portfolio.dto.IntegrityReportDto.Issue.builder()
                    .holdingId(h.getId()).symbol(h.getSymbol()).name(h.getName())
                    .type("DUPLICATE_SYMBOL")
                    .description(e.getKey() + " is held across " + group.size() + " separate holding rows (likely different portfolios) — the same position is being counted more than once. Safe to auto-merge.")
                    .currentValue(h.getCurrentValue())
                    .build());
            }
        }

        // 4b. The same ISIN under different symbols. An ISIN names one security (and, for a
        // fund, one plan and option), so two symbols sharing one are the same position imported
        // under two names — typically a fund whose name was spelled differently by two
        // statements. Merged by mergeDuplicateSymbols along with the exact-symbol case.
        for (List<Holding> group : identityGroups(all)) {
            long symbols = group.stream().map(h -> h.getSymbol().toUpperCase()).distinct().count();
            if (symbols <= 1) continue;
            for (Holding h : group) {
                issues.add(com.marketai.portfolio.dto.IntegrityReportDto.Issue.builder()
                    .holdingId(h.getId()).symbol(h.getSymbol()).name(h.getName())
                    .type("DUPLICATE_ISIN")
                    .description("ISIN " + h.getIsin() + " is held under " + symbols + " different names — the same security counted more than once. Safe to auto-merge.")
                    .currentValue(h.getCurrentValue())
                    .build());
            }
        }

        // 5. A wrong/truncated ticker that never received a live quote (Yahoo doesn't
        // recognise it — a strong, objective signal, unlike guessing from spelling alone).
        // Two sub-cases, resolved the same way the AI email extractor now validates NEW
        // imports (see AiEmailExtractor.resolveSymbol): search the stock master for the
        // held symbol/name.
        //   - Resolves to exactly one stock the user ALREADY holds under a different symbol
        //     -> MISMATCHED_TICKER, safe to auto-fix (fixMismatchedTickers): this is the same
        //     real position recorded under two different ticker spellings (ATHER.NS vs the
        //     real ATHERENERG.NS), not a guess — it's confirmed by the user's own other holding.
        //   - Resolves to nothing at all -> UNVERIFIABLE_SYMBOL, flagged for manual removal
        //     only (never auto-deleted — e.g. an ISIN fragment like "INE02" mistaken for a
        //     ticker has no safe automatic fix, just a clear "this isn't a real stock" flag).
        java.util.Set<String> heldSymbolsUpper = new java.util.HashSet<>();
        for (Holding h : all) if (h.getSymbol() != null) heldSymbolsUpper.add(h.getSymbol().toUpperCase());

        for (Holding h : all) {
            if (h.getSymbol() == null || h.getSymbol().toUpperCase().endsWith(".MF")) continue;
            if (h.getCurrentPrice() != null && h.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0) continue; // has a real live quote — not suspect

            String stripped = h.getSymbol().toUpperCase().replace(".NS", "").replace(".BO", "");
            String resolvedSymbol = resolveAgainstOwnHoldings(stripped, h, all, heldSymbolsUpper);

            // Only fall back to the live stock-search API (local seed list + Yahoo) when the
            // fully-local check above found nothing — that network call can be rate-limited
            // or simply unavailable, and this whole detection must still work when it is.
            List<com.marketai.market.entity.Stock> hits = java.util.Collections.emptyList();
            if (resolvedSymbol == null) {
                try { hits = marketDataService.searchStocks(stripped); } catch (Exception e) { hits = java.util.Collections.emptyList(); }
                for (com.marketai.market.entity.Stock s : hits) {
                    if (s.getSymbol() != null && heldSymbolsUpper.contains(s.getSymbol().toUpperCase())
                            && !s.getSymbol().equalsIgnoreCase(h.getSymbol())) {
                        resolvedSymbol = s.getSymbol();
                        break;
                    }
                }
            }

            if (resolvedSymbol != null) {
                issues.add(com.marketai.portfolio.dto.IntegrityReportDto.Issue.builder()
                    .holdingId(h.getId()).symbol(h.getSymbol()).name(h.getName())
                    .type("MISMATCHED_TICKER")
                    .description(h.getSymbol() + " never resolved to a live quote and appears to be the same position you already hold as " + resolvedSymbol + " — safe to auto-fix.")
                    .currentValue(h.getCurrentValue())
                    .resolvedSymbol(resolvedSymbol)
                    .build());
            } else if (hits.isEmpty()) {
                issues.add(com.marketai.portfolio.dto.IntegrityReportDto.Issue.builder()
                    .holdingId(h.getId()).symbol(h.getSymbol()).name(h.getName())
                    .type("UNVERIFIABLE_SYMBOL")
                    .description(h.getSymbol() + " does not match any known stock and has never received a live price — likely a parsing error (e.g. a fragment of an ISIN or client code), not a real ticker. Review and remove if incorrect.")
                    .currentValue(h.getCurrentValue())
                    .build());
            }
        }

        return com.marketai.portfolio.dto.IntegrityReportDto.builder()
            .totalHoldings(all.size())
            .issueCount(issues.size())
            .issues(issues)
            .build();
    }

    /**
     * Local-only resolution: does this wrong/truncated symbol look like a prefix of a
     * DIFFERENT symbol or name the same user already holds? Deliberately makes no network
     * call — the AI-extractor's live-search resolution can be rate-limited or unavailable,
     * and this exact bug (ATHER.NS vs the real ATHERENERG.NS, ADANI.NS vs ADANIENT.NS, etc.)
     * is fully determinable from the user's own portfolio data alone. Requires:
     *   - the fragment to be at least 4 characters (rules out coincidental short prefixes)
     *   - exactly ONE other held symbol/name to match, so an ambiguous fragment (could be
     *     either of two different real holdings) is left unresolved rather than guessed
     */
    private String resolveAgainstOwnHoldings(String strippedSymbol, Holding self, List<Holding> all, java.util.Set<String> heldSymbolsUpper) {
        if (strippedSymbol.length() < 4) return null;
        String candidates = null;
        int matchCount = 0;
        for (Holding other : all) {
            if (other.getId().equals(self.getId()) || other.getSymbol() == null) continue;
            if (other.getSymbol().toUpperCase().endsWith(".MF")) continue;
            String otherStripped = other.getSymbol().toUpperCase().replace(".NS", "").replace(".BO", "");
            if (otherStripped.equalsIgnoreCase(strippedSymbol)) continue; // same symbol — that's DUPLICATE_SYMBOL's job, not this
            String otherName = other.getName() != null ? other.getName().toUpperCase().replaceAll("[^A-Z0-9]", "") : "";
            boolean matches = otherStripped.startsWith(strippedSymbol) || otherName.startsWith(strippedSymbol);
            if (matches) {
                if (candidates != null && !candidates.equals(other.getSymbol())) return null; // ambiguous — more than one distinct candidate
                candidates = other.getSymbol();
                matchCount++;
            }
        }
        return matchCount > 0 ? candidates : null;
    }

    /**
     * Auto-fixes every MISMATCHED_TICKER case found by checkIntegrity: renames the wrong
     * ticker to match the confirmed-correct one already held, then merges the two rows. Only
     * acts on the high-confidence bucket (resolves to a symbol the user already holds) — never
     * touches UNVERIFIABLE_SYMBOL, which has no safe automatic fix and stays a manual removal.
     */
    @Transactional
    public com.marketai.portfolio.dto.MergeSummaryDto fixMismatchedTickers(Long userId) {
        com.marketai.portfolio.dto.IntegrityReportDto report = checkIntegrity(userId);
        List<com.marketai.portfolio.dto.MergeSummaryDto.MergedGroup> fixed = new ArrayList<>();
        int totalFixed = 0;

        for (com.marketai.portfolio.dto.IntegrityReportDto.Issue issue : report.getIssues()) {
            if (!"MISMATCHED_TICKER".equals(issue.getType()) || issue.getResolvedSymbol() == null) continue;
            Holding wrong = holdingRepository.findById(issue.getHoldingId())
                .filter(h -> h.getPortfolio().getUser().getId().equals(userId)).orElse(null);
            if (wrong == null) continue;

            // The confirmed-correct holding checkIntegrity resolved this against — may be in
            // a different portfolio than `wrong`.
            Holding correct = null;
            for (Portfolio p : portfolioRepository.findByUserId(userId)) {
                correct = holdingRepository.findByPortfolioIdAndSymbol(p.getId(), issue.getResolvedSymbol().toUpperCase()).orElse(null);
                if (correct != null) break;
            }
            if (correct == null) continue;

            String wrongSymbol = wrong.getSymbol();
            Holding merged = mergeHoldingsCore(correct, wrong, userId);
            fixed.add(com.marketai.portfolio.dto.MergeSummaryDto.MergedGroup.builder()
                .symbol(correct.getSymbol() + " (was " + wrongSymbol + ")")
                .keptHoldingId(merged.getId())
                .removedHoldingIds(java.util.Collections.singletonList(String.valueOf(wrong.getId())))
                .build());
            totalFixed++;
        }

        log.info("fixMismatchedTickers: user {} — {} mismatched ticker(s) resolved and merged", userId, totalFixed);
        return com.marketai.portfolio.dto.MergeSummaryDto.builder()
            .groupsMerged(fixed.size()).holdingsMerged(totalFixed).groups(fixed).build();
    }

    /**
     * Merges two holdings of the SAME symbol (across portfolios, or leftover from before the
     * unique constraint applied) into one: quantities add, average cost is recomputed as a
     * weighted average of both, every transaction from the removed holding is re-parented onto
     * the kept one (so nothing in the audit trail is lost), and the emptied holding is deleted.
     * Refuses outright if the two symbols differ — this must never be used to combine two
     * genuinely different positions.
     */
    @Transactional
    public Holding mergeHoldings(Long userId, Long keepHoldingId, Long removeHoldingId) {
        Holding keep = holdingRepository.findById(keepHoldingId)
            .filter(h -> h.getPortfolio().getUser().getId().equals(userId))
            .orElseThrow(() -> new ResourceNotFoundException("Holding", "id", keepHoldingId));
        Holding remove = holdingRepository.findById(removeHoldingId)
            .filter(h -> h.getPortfolio().getUser().getId().equals(userId))
            .orElseThrow(() -> new ResourceNotFoundException("Holding", "id", removeHoldingId));

        if (!keep.getSymbol().equalsIgnoreCase(remove.getSymbol())) {
            throw new IllegalArgumentException("Refusing to merge holdings with different symbols: "
                + keep.getSymbol() + " vs " + remove.getSymbol());
        }
        return mergeHoldingsCore(keep, remove, userId);
    }

    /**
     * Core merge, symbol-agnostic — used both by {@link #mergeHoldings} (which enforces the
     * same-symbol guard first) and by mismatched-ticker resolution, where `remove` legitimately
     * holds a different (wrong) symbol than `keep` and is deleted outright rather than ever
     * renamed to `keep`'s symbol — renaming it first would collide with the
     * (portfolio_id, symbol) unique constraint whenever both rows live in the same portfolio.
     */
    private Holding mergeHoldingsCore(Holding keep, Holding remove, Long userId) {
        Long keepHoldingId = keep.getId();
        Long removeHoldingId = remove.getId();
        String removeSymbol = remove.getSymbol();

        if (keep.getBroker() == null && remove.getBroker() != null) keep.setBroker(remove.getBroker());
        if (keep.getFolio() == null && remove.getFolio() != null) keep.setFolio(remove.getFolio());
        if (keep.getIsin() == null && remove.getIsin() != null) keep.setIsin(remove.getIsin());
        if (keep.getAmfiSchemeCode() == null && remove.getAmfiSchemeCode() != null) keep.setAmfiSchemeCode(remove.getAmfiSchemeCode());
        if (remove.getBuyDate() != null && (keep.getBuyDate() == null || remove.getBuyDate().isBefore(keep.getBuyDate()))) {
            keep.setBuyDate(remove.getBuyDate());
        }
        keep.setUpdatedAt(LocalDateTime.now());
        keep = holdingRepository.save(keep);

        for (Transaction t : transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(remove.getId())) {
            t.setHolding(keep);
            transactionRepository.save(t);
        }

        holdingRepository.delete(remove);
        // Both holdings held a positive quantity, so their combined ledger can never net to
        // zero or negative — this never returns null.
        keep = recomputeFromLedger(keep);
        log.info("Merged duplicate holding {} ({}) into {} for user {} — new qty={}, avgCost={}",
            removeHoldingId, removeSymbol, keepHoldingId, userId, keep.getQuantity(), keep.getAverageCost());
        return keep;
    }

    /**
     * Finds every symbol held across more than one portfolio for this user and merges each
     * group down to one holding via {@link #mergeHoldings}. Within a group, the holding with
     * the most transaction history (a tie-break proxy for "the original, most-established
     * record") is kept; ties broken by lowest id. Only ever combines exact symbol matches or
     * rows carrying the same ISIN — never touches the heuristic-based issues (UNVERIFIABLE_NAME/DUPLICATE_FOLIO/
     * DUPLICATE_DISPLAY_NAME), which still require a user's own informed removal via the UI.
     */
    @Transactional
    public com.marketai.portfolio.dto.MergeSummaryDto mergeDuplicateSymbols(Long userId) {
        List<Holding> all = new ArrayList<>();
        for (Portfolio p : portfolioRepository.findByUserId(userId)) {
            all.addAll(holdingRepository.findByPortfolioId(p.getId()));
        }

        List<com.marketai.portfolio.dto.MergeSummaryDto.MergedGroup> merged = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int totalMerged = 0;

        // Same symbol, or same ISIN under a different symbol — one security either way.
        for (List<Holding> group : identityGroups(all)) {
            if (group.size() <= 1) continue;
            // Two rows for one security are often the same purchases imported twice (a CAS and
            // the confirmation emails). Pooling their ledgers would then double the units, so a
            // group whose rows share an identical trade is reported, not merged.
            if (sharesATrade(group)) {
                skipped.add(group.get(0).getSymbol() + ": the rows contain the same trade twice — remove the duplicate trade, then merge");
                continue;
            }

            Holding keep = group.stream()
                .max(Comparator.comparingInt((Holding h) -> transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId()).size())
                    .thenComparing(Comparator.comparing(Holding::getId).reversed()))
                .orElse(group.get(0));

            List<String> mergedIds = new ArrayList<>();
            for (Holding h : group) {
                if (h.getId().equals(keep.getId())) continue;
                mergeHoldings(userId, keep.getId(), h.getId());
                mergedIds.add(String.valueOf(h.getId()));
                totalMerged++;
            }
            merged.add(com.marketai.portfolio.dto.MergeSummaryDto.MergedGroup.builder()
                .symbol(keep.getSymbol().toUpperCase()).keptHoldingId(keep.getId()).removedHoldingIds(mergedIds)
                .build());
        }

        log.info("mergeDuplicateSymbols: user {} — {} duplicate holding(s) merged across {} symbol group(s)",
            userId, totalMerged, merged.size());

        return com.marketai.portfolio.dto.MergeSummaryDto.builder()
            .groupsMerged(merged.size())
            .holdingsMerged(totalMerged)
            .skipped(skipped)
            .groups(merged)
            .build();
    }

    private boolean sharesATrade(List<Holding> group) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Holding h : group) {
            java.util.Set<String> own = new java.util.HashSet<>();
            for (Transaction t : transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId())) {
                String key = t.getType() + "|" + t.getTransactionDate() + "|"
                    + (t.getQuantity() == null ? "" : t.getQuantity().stripTrailingZeros().toPlainString()) + "|"
                    + (t.getPrice() == null ? "" : t.getPrice().stripTrailingZeros().toPlainString());
                own.add(key);
            }
            for (String k : own) if (!seen.add(k)) return true;
        }
        return false;
    }

    /**
     * Holdings that are one security: they share a symbol, or share an ISIN (transitively —
     * A and B by symbol, B and C by ISIN, is one group). Holdings with no symbol are left out.
     */
    static List<List<Holding>> identityGroups(List<Holding> holdings) {
        List<Holding> hs = holdings.stream().filter(h -> h.getSymbol() != null).toList();
        int[] parent = new int[hs.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
            public int applyAsInt(int i) { while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; }
        };
        java.util.Map<String, Integer> firstByKey = new java.util.HashMap<>();
        for (int i = 0; i < hs.size(); i++) {
            Holding h = hs.get(i);
            List<String> keys = new ArrayList<>();
            keys.add("S:" + h.getSymbol().toUpperCase());
            if (h.getIsin() != null && !h.getIsin().isBlank()) keys.add("I:" + h.getIsin().trim().toUpperCase());
            for (String k : keys) {
                Integer j = firstByKey.putIfAbsent(k, i);
                if (j != null) parent[find.applyAsInt(i)] = find.applyAsInt(j);
            }
        }
        java.util.Map<Integer, List<Holding>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < hs.size(); i++) groups.computeIfAbsent(find.applyAsInt(i), k -> new ArrayList<>()).add(hs.get(i));
        return new ArrayList<>(groups.values());
    }

    /**
     * Corrects a holding's symbol in place — for the "wrong ticker with no duplicate to
     * merge into" case (e.g. a holding recorded as "SBI.NS", which doesn't exist on NSE,
     * with no existing "SBIN.NS" holding to combine it with). Preserves quantity/cost/history
     * untouched; only the symbol string changes, so the position starts resolving to a real
     * quote instead of silently sitting at cost forever.
     */
    /**
     * Hand-verified mismatched-ticker fix for pairs where the wrong symbol shares no string
     * relationship with the correct one (e.g. LARSEN.NS vs LT.NS — both come from a fragment
     * of the company name, not the ticker) and so can't be found by the automated heuristic in
     * {@link #checkIntegrity}. Looks up both holdings and merges within one transaction (doing
     * the lookup outside a transaction would hit a lazy-init exception on the user/portfolio
     * association). No-ops (returns null) if either symbol isn't currently held.
     */
    @Transactional
    public Holding mergeHoldingsBySymbolPair(Long userId, String correctSymbol, String wrongSymbol) {
        Holding correct = null;
        Holding wrong = null;
        for (Portfolio p : portfolioRepository.findByUserId(userId)) {
            if (correct == null) correct = holdingRepository.findByPortfolioIdAndSymbol(p.getId(), correctSymbol.toUpperCase()).orElse(null);
            if (wrong == null) wrong = holdingRepository.findByPortfolioIdAndSymbol(p.getId(), wrongSymbol.toUpperCase()).orElse(null);
        }
        if (correct == null || wrong == null) return null;
        return mergeHoldingsCore(correct, wrong, userId);
    }

    @Transactional
    public Holding renameHoldingSymbol(Long userId, Long holdingId, String newSymbol) {
        Holding h = holdingRepository.findById(holdingId)
            .filter(x -> x.getPortfolio().getUser().getId().equals(userId))
            .orElseThrow(() -> new ResourceNotFoundException("Holding", "id", holdingId));
        String old = h.getSymbol();
        h.setSymbol(newSymbol.toUpperCase());
        h.setUpdatedAt(LocalDateTime.now());
        h = holdingRepository.save(h);
        log.info("Renamed holding {} symbol from {} to {} for user {}", holdingId, old, newSymbol, userId);
        return h;
    }

    @Transactional
    public void removeHolding(Long portfolioId, Long holdingId, Long userId) {
        Portfolio pf = portfolioRepository.findByIdAndUserId(portfolioId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));
        // Remove via the owning collection so orphanRemoval actually deletes the row
        // (a bare holdingRepository.deleteById gets re-persisted by the managed collection).
        boolean removed = pf.getHoldings().removeIf(h -> h.getId().equals(holdingId));
        // Not in this portfolio's collection means it is not this portfolio's holding — and since
        // the portfolio is the only thing ownership-checked here, deleting it anyway would let a
        // request delete any holding in the database by naming its id. It is a 404, not a fallback.
        if (!removed) throw new ResourceNotFoundException("Holding", "id", holdingId);
        portfolioRepository.save(pf);
    }

    /** Bulk-clear holdings. type = "stocks" (non-.MF), "mf", or "all". */
    @Transactional
    public int clearHoldings(Long portfolioId, Long userId, String type) {
        Portfolio pf = portfolioRepository.findByIdAndUserId(portfolioId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));
        int before = pf.getHoldings().size();
        pf.getHoldings().removeIf(h -> {
            boolean isMf = (h.getSymbol() != null && h.getSymbol().endsWith(".MF"));
            if ("stocks".equalsIgnoreCase(type)) return !isMf;
            if ("mf".equalsIgnoreCase(type))     return isMf;
            return true; // all
        });
        int removed = before - pf.getHoldings().size();
        portfolioRepository.save(pf);
        return removed;
    }

    /**
     * Correct a holding's quantity / average cost (e.g. after a stock split or bad import) and
     * optionally set a manual current price. For mutual funds the live-quote fetch fails, so a
     * manually-set price (NAV) persists and is used.
     *
     * Quantity/average-cost corrections are never applied by overwriting the holding's fields
     * directly — that let the displayed position silently diverge from what the transaction
     * ledger implies. Instead a corrective transaction pair is recorded (an offsetting SELL of
     * the prior position at its prior average cost, i.e. zero P&L, followed by a BUY at the
     * corrected quantity/cost) and the holding is then re-derived by replaying the ledger, so
     * the ledger stays the single source of truth even for manual fixes.
     */
    @Transactional
    public Holding updateHolding(Long portfolioId, Long holdingId, Long userId,
                                 BigDecimal quantity, BigDecimal averageCost, BigDecimal currentPrice,
                                 BigDecimal investedAmount, String broker, String folio,
                                 java.time.LocalDate buyDate, BigDecimal xirr) {
        portfolioRepository.findByIdAndUserId(portfolioId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));
        Holding h = holdingRepository.findByIdAndPortfolioId(holdingId, portfolioId)
                .orElseThrow(() -> new ResourceNotFoundException("Holding", "id", holdingId));

        BigDecimal targetQty = (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0) ? quantity : null;
        BigDecimal targetAvgCost = (averageCost != null && averageCost.compareTo(BigDecimal.ZERO) >= 0) ? averageCost : null;
        // If a total invested amount is given, derive avg cost from it (useful for MFs)
        BigDecimal qtyForDerivation = targetQty != null ? targetQty : h.getQuantity();
        if (investedAmount != null && investedAmount.compareTo(BigDecimal.ZERO) > 0
                && qtyForDerivation != null && qtyForDerivation.compareTo(BigDecimal.ZERO) > 0) {
            targetAvgCost = investedAmount.divide(qtyForDerivation, 4, RoundingMode.HALF_UP);
        }

        if (targetQty != null || targetAvgCost != null) {
            BigDecimal finalQty = targetQty != null ? targetQty : h.getQuantity();
            BigDecimal finalAvgCost = targetAvgCost != null ? targetAvgCost : h.getAverageCost();
            recordManualCorrection(h, finalQty, finalAvgCost);
            h = recomputeFromLedger(h); // finalQty > 0, so this never deletes/returns null
        }

        // A hand-entered price is as of today. Zero is not a price — it is ignored, not stored.
        if (currentPrice != null && currentPrice.signum() > 0) h.applyPrice(currentPrice, LocalDate.now());
        if (broker != null) h.setBroker(broker.trim().isEmpty() ? null : broker.trim());
        if (folio != null) h.setFolio(folio.trim().isEmpty() ? null : folio.trim());
        if (buyDate != null) h.setBuyDate(buyDate);
        if (xirr != null) h.setXirr(xirr);
        h.setUpdatedAt(LocalDateTime.now());
        return holdingRepository.save(h);
    }

    private void recordManualCorrection(Holding h, BigDecimal targetQty, BigDecimal targetAvgCost) {
        if (h.getQuantity() != null && h.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            transactionRepository.save(Transaction.builder()
                .holding(h).type(Transaction.TransactionType.SELL)
                .quantity(h.getQuantity()).price(h.getAverageCost()).charges(BigDecimal.ZERO)
                .transactionDate(LocalDate.now())
                .notes("Manual correction — offsetting prior position")
                .build());
        }
        transactionRepository.save(Transaction.builder()
            .holding(h).type(Transaction.TransactionType.BUY)
            .quantity(targetQty).price(targetAvgCost).charges(BigDecimal.ZERO)
            .transactionDate(LocalDate.now())
            .notes("Manual correction — corrected position")
            .build());
    }

    /**
     * Records a sale on the date it happened and replays the ledger.
     *
     * <p>A realized gain is not income: it is the difference between proceeds and cost on a
     * disposal that the SELL row itself records. Booking it as an Income row used to store a
     * capital <em>loss</em> as a positive "Capital Gain" (the sign was dropped), which the tax
     * estimate then taxed. Tax reads disposals from the ledger instead ({@link #realizedEquityGains}).
     *
     * @param tradeDate the day of the sale; null means today (a sale entered by hand as it happens)
     * @throws IllegalArgumentException when more units are sold than are held — a missing
     *         purchase, not something to paper over by clamping
     */
    @Transactional
    public void sellHolding(Long portfolioId, Long holdingId, Long userId,
                            BigDecimal qtySold, BigDecimal salePrice, LocalDate tradeDate) {
        sellHolding(portfolioId, holdingId, userId, qtySold, salePrice, tradeDate,
            com.marketai.common.ledger.Provenance.manual());
    }

    /** @param provenance where the sale was read from (the importer), or manual */
    @Transactional
    public void sellHolding(Long portfolioId, Long holdingId, Long userId,
                            BigDecimal qtySold, BigDecimal salePrice, LocalDate tradeDate,
                            com.marketai.common.ledger.Provenance provenance) {
        sellHolding(portfolioId, holdingId, userId, qtySold, salePrice, tradeDate, provenance, BigDecimal.ZERO);
    }

    /** @param charges brokerage and other charges on the sale (excluding STT), as stated */
    @Transactional
    public void sellHolding(Long portfolioId, Long holdingId, Long userId,
                            BigDecimal qtySold, BigDecimal salePrice, LocalDate tradeDate,
                            com.marketai.common.ledger.Provenance provenance, BigDecimal charges) {
        portfolioRepository.findByIdAndUserId(portfolioId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));
        Holding h = holdingRepository.findByIdAndPortfolioId(holdingId, portfolioId)
            .orElseThrow(() -> new ResourceNotFoundException("Holding", "id", holdingId));

        if (qtySold == null || qtySold.signum() <= 0) {
            throw new IllegalArgumentException("Sell quantity must be greater than zero.");
        }
        if (salePrice == null || salePrice.signum() <= 0) {
            throw new IllegalArgumentException("Sale price must be greater than zero.");
        }
        BigDecimal held = h.getQuantity() == null ? BigDecimal.ZERO : h.getQuantity();
        if (qtySold.compareTo(held) > 0) {
            throw new IllegalArgumentException("Cannot sell " + qtySold.stripTrailingZeros().toPlainString()
                + " units of " + h.getSymbol() + ": only " + held.stripTrailingZeros().toPlainString()
                + " are held. A purchase is probably missing from the history.");
        }
        LocalDate date = tradeDate != null ? tradeDate : LocalDate.now();
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Sale date " + date + " is in the future.");
        }
        // Units held today are not enough: a sale dated in the past needs the units held on its
        // date, and every later sale must still be covered once it is in the ledger.
        List<Transaction> ledger = transactionRepository.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId());
        if (!ledger.isEmpty()) {
            List<Transaction> withSale = new ArrayList<>(ledger);
            withSale.add(Transaction.builder().type(Transaction.TransactionType.SELL).quantity(qtySold)
                .price(salePrice).transactionDate(date).build());
            withSale.sort(Comparator.comparing(Transaction::getTransactionDate, Comparator.nullsFirst(Comparator.naturalOrder())));
            String shortfall = firstOversell(withSale);
            if (shortfall != null) {
                throw new IllegalArgumentException("Cannot record this sale of " + h.getSymbol() + ": " + shortfall
                    + " A purchase is probably missing from the history, or the date is wrong.");
            }
        }
        // Cost of the units sold, captured before the replay below revises the average cost
        // (and zeroes it when this sale closes the position).
        BigDecimal costBasis = h.getAverageCost().multiply(qtySold).setScale(2, RoundingMode.HALF_UP);

        transactionRepository.save(Transaction.builder()
            .holding(h)
            .type(Transaction.TransactionType.SELL)
            .quantity(qtySold)
            .price(salePrice)
            .charges(charges != null && charges.signum() > 0 ? charges : BigDecimal.ZERO)
            .transactionDate(date)
            .notes("Sale")
            .provenance(provenance)
            .build());

        recomputeFromLedger(h);

        // Mutual funds carry their STCG/LTCG record in MfRedemption. No try/catch fallback: if
        // it can't be written, the whole sale rolls back rather than half-recording.
        if (h.getSymbol() != null && h.getSymbol().endsWith(".MF")) {
            redemptionService.recordRedemption(userId, h, qtySold, salePrice, costBasis, date);
        }
    }

    /**
     * Realized gain (negative for a loss) on equity sales dated within [from, to], matched
     * first-in-first-out against purchase lots. Mutual funds are excluded — their disposals are
     * in MfRedemption.
     */
    @Transactional(readOnly = true)
    public BigDecimal realizedEquityGains(Long userId, LocalDate from, LocalDate to) {
        RealisedEquity r = realisedEquity(userId, from, to);
        return r.shortTerm().add(r.longTerm());
    }

    /** One equity sale's match against one purchase lot, with the holding it belongs to. */
    public record EquityDisposal(String symbol, String name, String isin,
                                 com.marketai.tax.lot.FifoLedger.Disposal disposal) {}

    /**
     * @param unmatchedUnits units sold (at any date) with no recorded purchase to match — their
     *                       gain is left out rather than guessed, and the caller must say so
     */
    public record RealisedEquity(BigDecimal shortTerm, BigDecimal longTerm, List<EquityDisposal> disposals,
                                 BigDecimal unmatchedUnits) {}

    /**
     * Realised gains on direct equity, matched first-in-first-out against purchase lots — the
     * statutory rule — so each rupee of gain is short- or long-term by the lot it came from.
     * The average-cost replay this replaced gave the right position cost but could not split
     * a sale across lots of different ages.
     */
    @Transactional(readOnly = true)
    public RealisedEquity realisedEquity(Long userId, LocalDate from, LocalDate to) {
        BigDecimal st = BigDecimal.ZERO, lt = BigDecimal.ZERO, unmatched = BigDecimal.ZERO;
        List<EquityDisposal> out = new ArrayList<>();
        for (Portfolio p : portfolioRepository.findByUserId(userId)) {
            for (Holding h : holdingRepository.findByPortfolioId(p.getId())) {
                if (h.getSymbol() == null || h.getSymbol().endsWith(".MF")) continue;
                List<com.marketai.tax.lot.FifoLedger.Trade> trades = transactionRepository
                    .findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId()).stream()
                    .map(com.marketai.tax.lot.FifoLedger.Trade::of)
                    .toList();
                com.marketai.tax.lot.FifoLedger.Result r = com.marketai.tax.lot.FifoLedger.replay(trades);
                st = st.add(r.shortTermGain(from, to));
                lt = lt.add(r.longTermGain(from, to));
                for (var d : r.disposals()) {
                    if (d.soldOn().isBefore(from) || d.soldOn().isAfter(to)) continue;
                    out.add(new EquityDisposal(h.getSymbol(), h.getName(), h.getIsin(), d));
                }
                unmatched = unmatched.add(r.unmatchedUnits());
            }
        }
        return new RealisedEquity(st.setScale(2, RoundingMode.HALF_UP), lt.setScale(2, RoundingMode.HALF_UP), out, unmatched);
    }
}
