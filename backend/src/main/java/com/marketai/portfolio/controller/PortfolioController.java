package com.marketai.portfolio.controller;

import com.marketai.auth.entity.User;
import com.marketai.portfolio.dto.AddHoldingRequest;
import com.marketai.portfolio.dto.PortfolioSummaryDto;
import com.marketai.portfolio.dto.TransactionDto;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.portfolio.service.PortfolioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolios")
@RequiredArgsConstructor
@Tag(name = "Portfolio", description = "Portfolio management and analytics")
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final com.marketai.income.repository.IncomeRepository incomeRepo;
    private final TransactionRepository transactionRepo;
    private final HoldingRepository holdingRepo;

    @PostMapping
    @Operation(summary = "Create a new portfolio")
    public ResponseEntity<Portfolio> createPortfolio(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, String> body) {
        Portfolio p = portfolioService.createPortfolio(
                user.getId(),
                body.getOrDefault("name", "My Portfolio"),
                body.get("description"));
        return ResponseEntity.status(HttpStatus.CREATED).body(p);
    }

    @GetMapping
    @Operation(summary = "List all portfolios for current user")
    public ResponseEntity<List<Portfolio>> getPortfolios(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(portfolioService.getUserPortfolios(user.getId()));
    }

    @GetMapping("/{id}/summary")
    @Operation(summary = "Get full portfolio summary with live prices and P&L")
    public ResponseEntity<PortfolioSummaryDto> getSummary(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(portfolioService.getPortfolioSummary(id, user.getId()));
    }

    @PostMapping("/{id}/holdings")
    @Operation(summary = "Add a holding (buy transaction)")
    public ResponseEntity<Holding> addHolding(
            @PathVariable Long id,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AddHoldingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(portfolioService.addHolding(id, user.getId(), request));
    }

    @DeleteMapping("/{portfolioId}/holdings/{holdingId}")
    @Operation(summary = "Remove a holding from portfolio")
    public ResponseEntity<Void> removeHolding(
            @PathVariable Long portfolioId,
            @PathVariable Long holdingId,
            @AuthenticationPrincipal User user) {
        portfolioService.removeHolding(portfolioId, holdingId, user.getId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{portfolioId}/holdings")
    @Operation(summary = "Bulk-clear holdings by type (stocks | mf | all)")
    public ResponseEntity<Map<String, Integer>> clearHoldings(
            @PathVariable Long portfolioId,
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "all") String type) {
        int removed = portfolioService.clearHoldings(portfolioId, user.getId(), type);
        return ResponseEntity.ok(java.util.Collections.singletonMap("removed", removed));
    }

    @PutMapping("/{portfolioId}/holdings/{holdingId}")
    @Operation(summary = "Correct a holding's quantity, average cost or current price")
    public ResponseEntity<Holding> updateHolding(
            @PathVariable Long portfolioId,
            @PathVariable Long holdingId,
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> body) {
        BigDecimal qty   = body.get("quantity") != null ? new BigDecimal(body.get("quantity").toString()) : null;
        BigDecimal avg   = body.get("averageCost") != null ? new BigDecimal(body.get("averageCost").toString()) : null;
        BigDecimal price = body.get("currentPrice") != null ? new BigDecimal(body.get("currentPrice").toString()) : null;
        BigDecimal inv   = body.get("investedAmount") != null ? new BigDecimal(body.get("investedAmount").toString()) : null;
        String broker    = body.get("broker") != null ? body.get("broker").toString() : null;
        String folio     = body.get("folio") != null ? body.get("folio").toString() : null;
        java.time.LocalDate buyDate = body.get("buyDate") != null ? java.time.LocalDate.parse(body.get("buyDate").toString()) : null;
        BigDecimal xirr  = body.get("xirr") != null ? new BigDecimal(body.get("xirr").toString()) : null;
        return ResponseEntity.ok(portfolioService.updateHolding(portfolioId, holdingId, user.getId(), qty, avg, price, inv, broker, folio, buyDate, xirr));
    }

    @PostMapping("/recalculate")
    @Operation(summary = "Force refresh all holding prices and recalculate portfolio metrics")
    public ResponseEntity<Map<String, String>> recalculate(@AuthenticationPrincipal User user) {
        portfolioService.refreshAllPrices(user.getId());
        return ResponseEntity.ok(java.util.Collections.singletonMap("status", "recalculated"));
    }

    @GetMapping("/integrity-check")
    @Operation(summary = "Audit all holdings for unverifiable names, duplicate folios, duplicate display names, and duplicate symbols across portfolios")
    public ResponseEntity<com.marketai.portfolio.dto.IntegrityReportDto> integrityCheck(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(portfolioService.checkIntegrity(user.getId()));
    }

    @PostMapping("/merge-duplicate-symbols")
    @Operation(summary = "Safely merges holdings that hold the exact same symbol across more than one portfolio (e.g. from the portfolio-fragmentation bug) into one, combining quantity/average cost and re-parenting every transaction — never touches holdings with merely similar names")
    public ResponseEntity<com.marketai.portfolio.dto.MergeSummaryDto> mergeDuplicateSymbols(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(portfolioService.mergeDuplicateSymbols(user.getId()));
    }

    @PostMapping("/fix-mismatched-tickers")
    @Operation(summary = "Auto-fixes holdings recorded under a wrong/truncated ticker (e.g. ATHER.NS) that never received a live quote and confidently resolve to a different symbol you already hold (e.g. ATHERENERG.NS) — renames and merges. Never touches a symbol that resolves to nothing (see integrity-check's UNVERIFIABLE_SYMBOL, which stays a manual review).")
    public ResponseEntity<com.marketai.portfolio.dto.MergeSummaryDto> fixMismatchedTickers(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(portfolioService.fixMismatchedTickers(user.getId()));
    }

    @PostMapping("/rebuild")
    @Operation(summary = "Rebuild all holdings from transaction history — fixes quantity/avg cost mismatches and removes fully-sold stocks")
    public ResponseEntity<Map<String, Object>> rebuild(@AuthenticationPrincipal User user) {
        int fixed = portfolioService.rebuildHoldingsFromTransactions(user.getId());
        portfolioService.refreshAllPrices(user.getId());
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("status", "rebuilt");
        resp.put("holdingsFixed", fixed);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/{portfolioId}/holdings/{holdingId}/sell")
    @Operation(summary = "Sell some or all of a holding")
    public ResponseEntity<Void> sellHolding(
            @PathVariable Long portfolioId,
            @PathVariable Long holdingId,
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> body) {
        BigDecimal qty   = new BigDecimal(body.get("quantity").toString());
        BigDecimal price = new BigDecimal(body.get("salePrice").toString());
        portfolioService.sellHolding(portfolioId, holdingId, user.getId(), qty, price, incomeRepo);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{portfolioId}/holdings/{holdingId}/transactions")
    @Operation(summary = "Get full transaction history for a holding")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<TransactionDto>> getTransactions(
            @PathVariable Long portfolioId,
            @PathVariable Long holdingId,
            @AuthenticationPrincipal User user) {
        Holding h = holdingRepo.findById(holdingId).orElse(null);
        if (h == null || !h.getPortfolio().getUser().getId().equals(user.getId())
                || !h.getPortfolio().getId().equals(portfolioId)) {
            return ResponseEntity.notFound().build();
        }
        List<Transaction> txns = transactionRepo.findByHoldingIdOrderByTransactionDateDesc(holdingId);
        List<TransactionDto> dtos = txns.stream().map(t -> toDto(t, h)).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/mf-transactions")
    @Operation(summary = "Recent mutual fund transactions across all portfolios")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<TransactionDto>> recentMfTransactions(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "7") int days) {
        int safeDays = Math.min(Math.max(days, 1), 365);
        java.time.LocalDate since = java.time.LocalDate.now().minusDays(safeDays);
        List<Transaction> txns = transactionRepo.findRecentMfTransactions(user.getId(), since);
        List<TransactionDto> dtos = txns.stream().map(t -> {
            Holding h = t.getHolding();
            return toDto(t, h);
        }).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    private TransactionDto toDto(Transaction t, Holding h) {
        return TransactionDto.builder()
                .id(t.getId())
                .holdingId(h.getId())
                .symbol(h.getSymbol())
                .fundName(h.getName())
                .type(t.getType().name())
                .quantity(t.getQuantity())
                .price(t.getPrice())
                .totalAmount(t.getTotalAmount())
                .charges(t.getCharges())
                .transactionDate(t.getTransactionDate())
                .notes(t.getNotes())
                .broker(h.getBroker())
                .folio(h.getFolio())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
