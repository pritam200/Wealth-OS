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

    @Transactional
    public Holding addHolding(Long portfolioId, Long userId, AddHoldingRequest req) {
        Portfolio portfolio = portfolioRepository.findByIdAndUserId(portfolioId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));

        Optional<Holding> existing = holdingRepository
                .findByPortfolioIdAndSymbol(portfolioId, req.getSymbol().toUpperCase());

        Holding holding;
        if (existing.isPresent()) {
            holding = existing.get();
            // Recalculate weighted average cost
            BigDecimal existingValue = holding.getAverageCost().multiply(holding.getQuantity());
            BigDecimal newValue = req.getPrice().multiply(req.getQuantity());
            BigDecimal totalQty = holding.getQuantity().add(req.getQuantity());
            BigDecimal newAvgCost = existingValue.add(newValue)
                    .divide(totalQty, 2, RoundingMode.HALF_UP);
            holding.setQuantity(totalQty);
            holding.setAverageCost(newAvgCost);
            holding.setUpdatedAt(LocalDateTime.now());
            // Fill broker/folio only if not already set
            if (holding.getBroker() == null && req.getBroker() != null) {
                holding.setBroker(req.getBroker());
            }
            if (holding.getFolio() == null && req.getFolio() != null) {
                holding.setFolio(req.getFolio());
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
                .build();
        transactionRepository.save(txn);

        return holding;
    }

    @Transactional
    public void refreshAllPrices(Long userId) {
        List<Portfolio> portfolios = portfolioRepository.findByUserId(userId);
        for (Portfolio portfolio : portfolios) {
            List<Holding> holdings = holdingRepository.findByPortfolioId(portfolio.getId());
            for (Holding h : holdings) {
                String sym = h.getSymbol();
                if (sym == null || sym.endsWith(".MF")) continue;
                try {
                    QuoteDto quote = marketDataService.getQuote(sym);
                    if (quote != null && quote.getCurrentPrice() != null
                            && quote.getCurrentPrice().signum() > 0) {
                        h.setCurrentPrice(quote.getCurrentPrice());
                        h.setUpdatedAt(LocalDateTime.now());
                        holdingRepository.save(h);
                    }
                } catch (Exception e) {
                    log.debug("Price refresh failed for {}: {}", sym, e.getMessage());
                }
            }
        }
    }

    @Transactional
    public int rebuildHoldingsFromTransactions(Long userId) {
        List<Portfolio> portfolios = portfolioRepository.findByUserId(userId);
        int fixed = 0;
        for (Portfolio portfolio : portfolios) {
            List<Holding> holdings = holdingRepository.findByPortfolioId(portfolio.getId());
            for (Holding h : holdings) {
                List<Transaction> txns = transactionRepository.findByHoldingIdOrderByTransactionDateAsc(h.getId());
                if (txns.isEmpty()) continue;
                BigDecimal netQty = BigDecimal.ZERO;
                BigDecimal totalCost = BigDecimal.ZERO;
                for (Transaction t : txns) {
                    if (t.getType() == Transaction.TransactionType.BUY) {
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
                BigDecimal newAvgCost = netQty.compareTo(BigDecimal.ZERO) > 0
                    ? totalCost.divide(netQty, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                if (netQty.compareTo(BigDecimal.ZERO) <= 0) {
                    holdingRepository.delete(h);
                    fixed++;
                    log.info("Removed fully sold holding: {}", h.getSymbol());
                } else if (netQty.compareTo(h.getQuantity()) != 0 || newAvgCost.compareTo(h.getAverageCost()) != 0) {
                    h.setQuantity(netQty);
                    h.setAverageCost(newAvgCost);
                    h.setUpdatedAt(LocalDateTime.now());
                    holdingRepository.save(h);
                    fixed++;
                    log.info("Rebuilt holding {}: qty={}, avgCost={}", h.getSymbol(), netQty, newAvgCost);
                }
            }
        }
        return fixed;
    }

    @Transactional(readOnly = true)
    public PortfolioSummaryDto getPortfolioSummary(Long portfolioId, Long userId) {
        Portfolio portfolio = portfolioRepository.findByIdAndUserId(portfolioId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));

        List<Holding> holdings = holdingRepository.findByPortfolioId(portfolioId);

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
                    h.setCurrentPrice(quote.getCurrentPrice());
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

        List<PortfolioSummaryDto.HoldingDto> holdingDtos = holdings.stream()
                .map(h -> {
                    BigDecimal weight = currentValue.compareTo(BigDecimal.ZERO) != 0
                            ? h.getCurrentValue().divide(currentValue, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                            : BigDecimal.ZERO;
                    return PortfolioSummaryDto.HoldingDto.builder()
                            .id(h.getId())
                            .portfolioId(portfolioId)
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
                            .buyDate(h.getBuyDate())
                            .xirr(h.getXirr())
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
                .name(portfolio.getName())
                .totalInvested(totalInvested)
                .currentValue(currentValue)
                .totalPnl(totalPnl)
                .totalPnlPercent(totalPnlPct)
                .holdings(holdingDtos)
                .allocation(allocation)
                .lastUpdated(LocalDateTime.now())
                .build();
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
            all.addAll(holdingRepository.findByPortfolioId(p.getId()));
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

        return com.marketai.portfolio.dto.IntegrityReportDto.builder()
            .totalHoldings(all.size())
            .issueCount(issues.size())
            .issues(issues)
            .build();
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

        BigDecimal keepValue = keep.getAverageCost().multiply(keep.getQuantity());
        BigDecimal removeValue = remove.getAverageCost().multiply(remove.getQuantity());
        BigDecimal totalQty = keep.getQuantity().add(remove.getQuantity());
        if (totalQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal newAvgCost = keepValue.add(removeValue).divide(totalQty, 2, RoundingMode.HALF_UP);
            keep.setQuantity(totalQty);
            keep.setAverageCost(newAvgCost);
        }
        if (keep.getBroker() == null && remove.getBroker() != null) keep.setBroker(remove.getBroker());
        if (keep.getFolio() == null && remove.getFolio() != null) keep.setFolio(remove.getFolio());
        if (remove.getBuyDate() != null && (keep.getBuyDate() == null || remove.getBuyDate().isBefore(keep.getBuyDate()))) {
            keep.setBuyDate(remove.getBuyDate());
        }
        keep.setUpdatedAt(LocalDateTime.now());
        keep = holdingRepository.save(keep);

        for (Transaction t : transactionRepository.findByHoldingIdOrderByTransactionDateAsc(remove.getId())) {
            t.setHolding(keep);
            transactionRepository.save(t);
        }

        holdingRepository.delete(remove);
        log.info("Merged duplicate holding {} ({}) into {} for user {} — new qty={}, avgCost={}",
            removeHoldingId, remove.getSymbol(), keepHoldingId, userId, keep.getQuantity(), keep.getAverageCost());
        return keep;
    }

    /**
     * Finds every symbol held across more than one portfolio for this user and merges each
     * group down to one holding via {@link #mergeHoldings}. Within a group, the holding with
     * the most transaction history (a tie-break proxy for "the original, most-established
     * record") is kept; ties broken by lowest id. Only ever combines EXACT symbol matches —
     * never touches the heuristic-based issues (UNVERIFIABLE_NAME/DUPLICATE_FOLIO/
     * DUPLICATE_DISPLAY_NAME), which still require a user's own informed removal via the UI.
     */
    @Transactional
    public com.marketai.portfolio.dto.MergeSummaryDto mergeDuplicateSymbols(Long userId) {
        List<Holding> all = new ArrayList<>();
        for (Portfolio p : portfolioRepository.findByUserId(userId)) {
            all.addAll(holdingRepository.findByPortfolioId(p.getId()));
        }

        java.util.Map<String, List<Holding>> bySymbol = new java.util.LinkedHashMap<>();
        for (Holding h : all) {
            if (h.getSymbol() == null) continue;
            bySymbol.computeIfAbsent(h.getSymbol().toUpperCase(), k -> new ArrayList<>()).add(h);
        }

        List<com.marketai.portfolio.dto.MergeSummaryDto.MergedGroup> merged = new ArrayList<>();
        int totalMerged = 0;

        for (java.util.Map.Entry<String, List<Holding>> e : bySymbol.entrySet()) {
            List<Holding> group = e.getValue();
            if (group.size() <= 1) continue;

            Holding keep = group.stream()
                .max(Comparator.comparingInt((Holding h) -> transactionRepository.findByHoldingIdOrderByTransactionDateAsc(h.getId()).size())
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
                .symbol(e.getKey()).keptHoldingId(keep.getId()).removedHoldingIds(mergedIds)
                .build());
        }

        log.info("mergeDuplicateSymbols: user {} — {} duplicate holding(s) merged across {} symbol group(s)",
            userId, totalMerged, merged.size());

        return com.marketai.portfolio.dto.MergeSummaryDto.builder()
            .groupsMerged(merged.size())
            .holdingsMerged(totalMerged)
            .groups(merged)
            .build();
    }

    @Transactional
    public void removeHolding(Long portfolioId, Long holdingId, Long userId) {
        Portfolio pf = portfolioRepository.findByIdAndUserId(portfolioId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));
        // Remove via the owning collection so orphanRemoval actually deletes the row
        // (a bare holdingRepository.deleteById gets re-persisted by the managed collection).
        boolean removed = pf.getHoldings().removeIf(h -> h.getId().equals(holdingId));
        if (removed) portfolioRepository.save(pf);
        else holdingRepository.deleteById(holdingId);
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
     * Correct a holding's quantity / average cost (e.g. after a stock split or
     * bad import) and optionally set a manual current price. For mutual funds the
     * live-quote fetch fails, so a manually-set price (NAV) persists and is used.
     */
    @Transactional
    public Holding updateHolding(Long portfolioId, Long holdingId, Long userId,
                                 BigDecimal quantity, BigDecimal averageCost, BigDecimal currentPrice,
                                 BigDecimal investedAmount, String broker, String folio,
                                 java.time.LocalDate buyDate, BigDecimal xirr) {
        portfolioRepository.findByIdAndUserId(portfolioId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));
        Holding h = holdingRepository.findById(holdingId)
                .orElseThrow(() -> new ResourceNotFoundException("Holding", "id", holdingId));
        if (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0) h.setQuantity(quantity);
        if (averageCost != null && averageCost.compareTo(BigDecimal.ZERO) >= 0) h.setAverageCost(averageCost);
        // If a total invested amount is given, derive avg cost from it (useful for MFs)
        if (investedAmount != null && investedAmount.compareTo(BigDecimal.ZERO) > 0
                && h.getQuantity() != null && h.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            h.setAverageCost(investedAmount.divide(h.getQuantity(), 4, RoundingMode.HALF_UP));
        }
        if (currentPrice != null && currentPrice.compareTo(BigDecimal.ZERO) >= 0) h.setCurrentPrice(currentPrice);
        if (broker != null) h.setBroker(broker.trim().isEmpty() ? null : broker.trim());
        if (folio != null) h.setFolio(folio.trim().isEmpty() ? null : folio.trim());
        if (buyDate != null) h.setBuyDate(buyDate);
        if (xirr != null) h.setXirr(xirr);
        h.setUpdatedAt(LocalDateTime.now());
        return holdingRepository.save(h);
    }

    @Transactional
    public void sellHolding(Long portfolioId, Long holdingId, Long userId,
                            java.math.BigDecimal qtySold, java.math.BigDecimal salePrice,
                            com.marketai.income.repository.IncomeRepository incomeRepo) {
        portfolioRepository.findByIdAndUserId(portfolioId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Portfolio", "id", portfolioId));
        Holding h = holdingRepository.findById(holdingId)
            .orElseThrow(() -> new ResourceNotFoundException("Holding", "id", holdingId));

        if (qtySold.compareTo(h.getQuantity()) > 0) {
            log.warn("Sell qty {} exceeds holding qty {} for {} — clamping to available", qtySold, h.getQuantity(), h.getSymbol());
            qtySold = h.getQuantity();
        }
        java.math.BigDecimal newQty = h.getQuantity().subtract(qtySold);
        java.math.BigDecimal saleValue = salePrice.multiply(qtySold);
        java.math.BigDecimal costBasis = h.getAverageCost().multiply(qtySold);
        java.math.BigDecimal pnl = saleValue.subtract(costBasis).setScale(2, RoundingMode.HALF_UP);

        // Record the SELL transaction before potentially deleting the holding
        Transaction sellTxn = Transaction.builder()
            .holding(h)
            .type(Transaction.TransactionType.SELL)
            .quantity(qtySold)
            .price(salePrice)
            .charges(BigDecimal.ZERO)
            .transactionDate(LocalDate.now())
            .notes("Auto-recorded from sell")
            .build();
        transactionRepository.save(sellTxn);

        if (newQty.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            holdingRepository.deleteById(holdingId);
        } else {
            h.setQuantity(newQty);
            holdingRepository.save(h);
        }

        // Auto-record capital gain/loss as income
        String desc = (pnl.compareTo(java.math.BigDecimal.ZERO) >= 0 ? "Capital Gain" : "Capital Loss")
            + " — " + h.getSymbol().replace(".NS", "");
        com.marketai.income.entity.Income inc = com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description(desc)
            .amount(pnl.abs())
            .source("Capital Gain")
            .incomeDate(java.time.LocalDate.now())
            .note("Sold " + qtySold.stripTrailingZeros().toPlainString() + " units @ ₹" + salePrice + ". PnL: ₹" + pnl)
            .build();
        incomeRepo.save(inc);

        // Additive lifecycle tracking for mutual funds only — doesn't change existing sell
        // behavior, just gives the redemption a persistent record (STCG/LTCG split, cash
        // available) instead of only leaving the single Income row above.
        if (h.getSymbol() != null && h.getSymbol().endsWith(".MF")) {
            try {
                redemptionService.recordRedemption(userId, h, qtySold, salePrice);
            } catch (Exception e) {
                log.warn("Could not record MF redemption for holding {}: {}", holdingId, e.getMessage());
            }
        }
    }
}
