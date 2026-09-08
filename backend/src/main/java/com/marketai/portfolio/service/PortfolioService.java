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

        // A BUY only ever increases net quantity, so this never returns null (never deletes).
        return recomputeFromLedger(holding);
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

    /**
     * Runs {@link #rebuildHoldingsFromTransactions} for every user — the nightly reconciliation
     * pass that catches any drift between a holding's stored quantity/averageCost and what its
     * transaction ledger actually implies, before a user ever notices a wrong number on screen.
     */
    public void reconcileAllUsers() {
        for (User u : userRepository.findAll()) {
            try {
                int fixed = rebuildHoldingsFromTransactions(u.getId());
                if (fixed > 0) {
                    log.warn("Nightly reconciliation: user {} had {} holding(s) drifted from their transaction ledger — corrected", u.getId(), fixed);
                }
            } catch (Exception e) {
                log.error("Nightly reconciliation failed for user {}: {}", u.getId(), e.getMessage());
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
                if (transactionRepository.findByHoldingIdOrderByTransactionDateAsc(h.getId()).isEmpty()) continue;
                BigDecimal beforeQty = h.getQuantity();
                BigDecimal beforeAvgCost = h.getAverageCost();
                Holding after = recomputeFromLedger(h);
                if (after == null) {
                    fixed++;
                    log.info("Removed fully sold holding: {}", h.getSymbol());
                } else if (after.getQuantity().compareTo(beforeQty) != 0 || after.getAverageCost().compareTo(beforeAvgCost) != 0) {
                    fixed++;
                    log.info("Rebuilt holding {}: qty={}, avgCost={}", after.getSymbol(), after.getQuantity(), after.getAverageCost());
                }
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
    private BigDecimal computeRealXirr(Holding h) {
        List<Transaction> txns = transactionRepository.findByHoldingIdOrderByTransactionDateAsc(h.getId());
        if (txns.isEmpty()) return h.getXirr();

        List<com.marketai.portfolio.util.XirrCalculator.CashFlow> flows = new ArrayList<>();
        for (Transaction t : txns) {
            if (t.getTransactionDate() == null || t.getPrice() == null || t.getQuantity() == null) continue;
            BigDecimal flowAmount = t.getPrice().multiply(t.getQuantity());
            if (t.getType() == Transaction.TransactionType.BUY) flowAmount = flowAmount.negate();
            flows.add(new com.marketai.portfolio.util.XirrCalculator.CashFlow(t.getTransactionDate(), flowAmount));
        }
        BigDecimal currentValue = h.getCurrentValue();
        if (currentValue != null && currentValue.compareTo(BigDecimal.ZERO) > 0) {
            flows.add(new com.marketai.portfolio.util.XirrCalculator.CashFlow(LocalDate.now(), currentValue));
        }

        Double xirrPct = com.marketai.portfolio.util.XirrCalculator.computeXirrPercent(flows);
        return xirrPct != null ? BigDecimal.valueOf(xirrPct).setScale(2, RoundingMode.HALF_UP) : h.getXirr();
    }

    /**
     * The single place quantity/averageCost is ever derived — always by replaying the full
     * BUY/SELL transaction ledger for this holding, never by hand-rolling weighted-average
     * math inline (that pattern used to be duplicated across addHolding/sellHolding/merge,
     * and could silently drift from what the ledger actually implies). Deletes the holding
     * and returns null if the ledger nets to zero or negative quantity (fully sold).
     */
    private Holding recomputeFromLedger(Holding h) {
        List<Transaction> txns = transactionRepository.findByHoldingIdOrderByTransactionDateAsc(h.getId());
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
        if (netQty.compareTo(BigDecimal.ZERO) <= 0) {
            holdingRepository.delete(h);
            return null;
        }
        BigDecimal newAvgCost = totalCost.divide(netQty, 2, RoundingMode.HALF_UP);
        h.setQuantity(netQty);
        h.setAverageCost(newAvgCost);
        h.setUpdatedAt(LocalDateTime.now());
        return holdingRepository.save(h);
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
                            .xirr(computeRealXirr(h))
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
        Holding h = holdingRepository.findById(holdingId)
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

        if (currentPrice != null && currentPrice.compareTo(BigDecimal.ZERO) >= 0) h.setCurrentPrice(currentPrice);
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
        // Realized P&L is booked against the average cost as it stood before this sale —
        // capture it now, before the ledger recompute below revises the holding's balance.
        java.math.BigDecimal saleValue = salePrice.multiply(qtySold);
        java.math.BigDecimal costBasis = h.getAverageCost().multiply(qtySold);
        java.math.BigDecimal pnl = saleValue.subtract(costBasis).setScale(2, RoundingMode.HALF_UP);

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

        // Replays the full ledger (including this sale) — deletes the holding itself if this
        // sale exhausted the position, exactly like every other holding-quantity mutation.
        recomputeFromLedger(h);

        // For mutual funds, MfRedemption (STCG/LTCG split, reinvestment tracking) is the one
        // realized-gain record — a generic Income "Capital Gain" row on top of it used to
        // double-book the same rupees under two independent records. Only fall back to the
        // generic Income row if the MF redemption record couldn't be created, so the gain is
        // never silently lost.
        boolean mfRedemptionRecorded = false;
        if (h.getSymbol() != null && h.getSymbol().endsWith(".MF")) {
            try {
                redemptionService.recordRedemption(userId, h, qtySold, salePrice);
                mfRedemptionRecorded = true;
            } catch (Exception e) {
                log.warn("Could not record MF redemption for holding {}: {}", holdingId, e.getMessage());
            }
        }

        if (!mfRedemptionRecorded) {
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
        }
    }
}
