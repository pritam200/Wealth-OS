package com.marketai.dataplatform.service;

import com.marketai.common.ledger.Provenance;
import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.repo.CanonicalTransactionRepository;
import com.marketai.dataplatform.repo.FinancialAssetRepository;
import com.marketai.auth.repository.UserRepository;
import com.marketai.dataplatform.repo.FinancialAccountRepository;
import com.marketai.income.entity.Income;
import com.marketai.income.entity.IncomeSource;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.portfolio.dto.AddHoldingRequest;
import com.marketai.tracking.dto.FdRequest;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.service.TrackingService;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.portfolio.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Books a transaction the user has just confirmed in the ledger into the legacy portfolio the rest
 * of the app reads, through the portfolio service's own validated paths (so oversell, future-date
 * and duplicate checks all apply). This is only ever triggered by a person's confirmation: the
 * platform never rewrites the portfolio on its own. The legacy row carries a provenance fingerprint
 * of the canonical id and the canonical entry records the legacy id, so the backfill cannot book it
 * again. Anything it cannot book safely is reported with a reason and left for the user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LegacyApplyService {

    public record Result(boolean applied, String message) {
        static Result no(String why) { return new Result(false, why); }
    }

    private final ObjectProvider<PortfolioService> portfolioService;
    private final ObjectProvider<HoldingRepository> holdingRepo;
    private final ObjectProvider<TransactionRepository> legacyTxns;
    private final CanonicalTransactionRepository canonical;
    private final FinancialAssetRepository assets;
    private final PlatformTransactionManager tm;
    private final ObjectProvider<IncomeRepository> incomeRepo;
    private final ObjectProvider<FixedDepositRepository> fdRepo;
    private final ObjectProvider<TrackingService> tracking;
    private final ObjectProvider<UserRepository> users;
    private final FinancialAccountRepository accounts;

    public Result apply(CanonicalTransaction t) {
        PortfolioService ps = portfolioService.getIfAvailable();
        if (ps == null) return Result.no("The portfolio module is not available.");
        if (t.getLegacyTransactionId() != null || t.getPortfolioRef() != null) return Result.no("Already in your portfolio.");
        if (!t.getStatus().counts()) return Result.no("The transaction is not active.");
        switch (t.getTransactionType()) {
            case INTEREST, DIVIDEND -> { return applyIncome(t); }
            case FD_CREATION -> { return applyFdOpen(t); }
            case FD_MATURITY -> { return applyFdClose(t); }
            default -> { }
        }
        if (t.getAssetId() == null) return Result.no("It has no security attached, so it was not added to the portfolio.");
        TransactionType.Family fam = t.getTransactionType().family();
        boolean buy = fam == TransactionType.Family.PURCHASE, sell = fam == TransactionType.Family.SALE;
        boolean merger = t.getTransactionType() == TransactionType.MERGER;
        boolean split = t.getTransactionType() == TransactionType.SPLIT, bonus = t.getTransactionType() == TransactionType.BONUS;
        if (!buy && !sell && !split && !bonus && !merger)
            return Result.no(t.getTransactionType() + " is recorded in the ledger but is not added to the portfolio automatically.");
        BigDecimal qty = t.getQuantity();
        BigDecimal price = t.getUnitPrice();
        BigDecimal amount = t.getNetAmount() != null ? t.getNetAmount() : t.getGrossAmount();
        if (price == null && qty != null && qty.signum() > 0 && amount != null) price = amount.divide(qty, 4, RoundingMode.HALF_UP);
        if ((buy || sell) && (qty == null || qty.signum() <= 0 || price == null || price.signum() <= 0))
            return Result.no("Units or price are not known, so it was not added to the portfolio.");
        if (split && (t.getRatioFrom() == null || t.getRatioTo() == null))
            return Result.no("The split ratio is not known, so it was not added to the portfolio.");
        if (merger && (t.getRatioFrom() == null || t.getRatioTo() == null))
            return Result.no("The merger's exchange ratio is not known, so it was not added to the portfolio.");
        if (merger && (t.getNewSymbol() == null || t.getNewSymbol().isBlank()))
            return Result.no("The ledger does not say which company's shares replace these, so the merger was not added to the portfolio.");
        if (bonus && (qty == null || qty.signum() <= 0) && (t.getRatioFrom() == null || t.getRatioTo() == null))
            return Result.no("Neither the bonus units nor the ratio are known, so it was not added to the portfolio.");

        FinancialAsset a = assets.findById(t.getAssetId()).orElse(null);
        if (a == null) return Result.no("The security could not be found.");
        List<Portfolio> portfolios = ps.getUserPortfolios(t.getUserId());
        if (portfolios.isEmpty()) return Result.no("You have no portfolio to add it to.");
        Portfolio portfolio = portfolios.get(0);

        boolean mf = a.getAssetClass() == AssetClass.MUTUAL_FUND;
        String symbol = symbolFor(ps, portfolio.getId(), a, mf);
        if (symbol == null) return Result.no("The security has no symbol or name to file it under.");

        Provenance prov = Provenance.builder().sourceFingerprint("canonical-" + t.getId()).extractionMethod("CANONICAL_LEDGER")
            .sourceReference("canonical-" + t.getId()).extractionConfidence(t.getConfidence()).build();
        TransactionTemplate isolated = new TransactionTemplate(tm);
        // Its own transaction: a refusal from the portfolio (oversell, ISIN clash) rolls back only the
        // portfolio write, never the user's confirmation in the ledger.
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        final BigDecimal fPrice = price;
        try {
            Long legacyId = isolated.execute(st -> {
                Long holdingId;
                if (split || bonus || merger) {
                    holdingId = ps.findHoldingId(portfolio.getId(), symbol);
                    if (holdingId == null) throw new IllegalArgumentException("you hold none of " + symbol + " in the portfolio");
                    PortfolioService.CorporateActionResult r = merger
                        ? ps.recordMerger(portfolio.getId(), symbol, mergerTarget(symbol, t.getNewSymbol()), t.getRatioFrom(), t.getRatioTo(), t.getTransactionDate(), prov)
                        : split
                        ? ps.recordSplit(portfolio.getId(), symbol, t.getRatioFrom(), t.getRatioTo(), t.getTransactionDate(), prov)
                        : ps.recordBonus(portfolio.getId(), symbol, t.getRatioFrom(), t.getRatioTo(), qty, t.getTransactionDate(), prov);
                    if (r.duplicate()) throw new IllegalArgumentException(r.detail());
                } else if (buy) {
                    AddHoldingRequest req = new AddHoldingRequest();
                    req.setSymbol(symbol); req.setName(a.getName() != null ? a.getName() : symbol);
                    req.setQuantity(qty); req.setPrice(fPrice); req.setTransactionDate(t.getTransactionDate());
                    req.setCharges(t.getFees() == null ? BigDecimal.ZERO : t.getFees());
                    req.setIsin(a.getIsin()); req.setProvenance(prov);
                    req.setNotes("Added from the verified ledger after you confirmed it");
                    holdingId = ps.addHolding(portfolio.getId(), t.getUserId(), req).getId();
                } else {
                    holdingId = ps.findHoldingId(portfolio.getId(), symbol);
                    if (holdingId == null) throw new IllegalArgumentException("you hold none of " + symbol + " in the portfolio");
                    ps.sellHolding(portfolio.getId(), holdingId, t.getUserId(), qty, fPrice, t.getTransactionDate(), prov,
                        t.getFees() == null ? BigDecimal.ZERO : t.getFees());
                }
                return legacyTxns.getObject().findByHoldingIdOrderByTransactionDateAscIdAsc(holdingId).stream()
                    .filter(x -> x.getProvenance() != null && ("canonical-" + t.getId()).equals(x.getProvenance().getSourceFingerprint()))
                    .map(Transaction::getId).findFirst().orElse(null);
            });
            if (legacyId != null) t.setLegacyTransactionId(legacyId); else t.setPortfolioRef("applied");
            if (t.getId() != null && canonical.existsById(t.getId())) canonical.save(t);
            return new Result(true, "Added to your portfolio.");
        } catch (IllegalArgumentException e) {
            // Oversell, future date, ISIN clash: the portfolio's own validation, shown to the user as is.
            return Result.no("Not added to the portfolio: " + e.getMessage());
        }
    }

    /** The surviving company keeps the exchange the old one traded on unless the source named one. */
    private static String mergerTarget(String oldSymbol, String newSymbol) {
        String n = newSymbol.trim().toUpperCase();
        if (n.contains(".")) return n;
        return n + (oldSymbol != null && oldSymbol.toUpperCase().endsWith(".BO") ? ".BO" : ".NS");
    }

    private String institutionOf(CanonicalTransaction t) {
        return accounts.findById(t.getAccountId()).map(FinancialAccount::getInstitution)
            .filter(i -> i != null && !i.isBlank()).orElse(null);
    }

    private Result record(CanonicalTransaction t, String ref, String message) {
        t.setPortfolioRef(ref);
        canonical.save(t);
        return new Result(true, message);
    }

    private TransactionTemplate isolated() {
        TransactionTemplate tt = new TransactionTemplate(tm);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt;
    }

    /** Interest and dividends become income entries, booked gross with the TDS beside, as the FD close does. */
    private Result applyIncome(CanonicalTransaction t) {
        IncomeRepository repo = incomeRepo.getIfAvailable();
        if (repo == null) return Result.no("The income module is not available.");
        BigDecimal tds = t.getTaxes() != null && t.getTaxes().signum() > 0 ? t.getTaxes() : null;
        BigDecimal gross = t.getGrossAmount() != null ? t.getGrossAmount()
            : t.getNetAmount() == null ? null : t.getNetAmount().add(tds == null ? BigDecimal.ZERO : tds);
        if (gross == null || gross.signum() <= 0) return Result.no("The amount is not known, so it was not added to your income.");
        boolean dividend = t.getTransactionType() == TransactionType.DIVIDEND;
        IncomeSource source = dividend ? IncomeSource.DIVIDEND : IncomeSource.INTEREST;
        String who = dividend
            ? t.getAssetId() == null ? null : assets.findById(t.getAssetId()).map(a -> a.getName() != null ? a.getName() : a.getSymbol()).orElse(null)
            : institutionOf(t);
        String description = (dividend ? "Dividend" : "Interest") + (who == null ? "" : " — " + who);
        if (description.length() > 200) description = description.substring(0, 200);
        // The same credit may already have come in by email; link to that instead of booking it twice.
        for (Income i : repo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(t.getUserId(), t.getTransactionDate(), t.getTransactionDate())) {
            if (i.getSource() == source && i.getSourceEmailId() != null && i.getAmount() != null
                && (i.getAmount().compareTo(gross) == 0 || (t.getNetAmount() != null && i.getAmount().compareTo(t.getNetAmount()) == 0)))
                return record(t, "income:" + i.getId(), "This is already in your income records (from email), so it was linked instead of added again.");
        }
        final String desc = description;
        Long id = isolated().execute(st -> repo.save(Income.builder().userId(t.getUserId()).description(desc).amount(gross)
            .source(source).incomeDate(t.getTransactionDate()).tds(tds)
            .sourceFingerprint("canonical-" + t.getId())
            .note("Added from the verified ledger after you confirmed it").build()).getId());
        return record(t, "income:" + id, "Added to your income.");
    }

    /** A fixed deposit opened: tracked only when the source gave the rate and maturity, never guessed. */
    private Result applyFdOpen(CanonicalTransaction t) {
        TrackingService ts = tracking.getIfAvailable();
        FixedDepositRepository repo = fdRepo.getIfAvailable();
        UserRepository ur = users.getIfAvailable();
        if (ts == null || repo == null || ur == null) return Result.no("The deposit tracker is not available.");
        FinancialAsset a = t.getAssetId() == null ? null : assets.findById(t.getAssetId()).orElse(null);
        if (a != null && a.getAssetClass() == AssetClass.RD) return Result.no("Recurring deposits are recorded in the ledger but not added to the deposit tracker automatically.");
        String bank = institutionOf(t);
        BigDecimal principal = t.getGrossAmount() != null ? t.getGrossAmount() : t.getNetAmount();
        if (bank == null) return Result.no("The bank is not known, so the deposit was not added to the tracker.");
        if (principal == null || principal.signum() <= 0) return Result.no("The deposit amount is not known, so it was not added to the tracker.");
        BigDecimal rate = t.getInterestRate();
        if (rate == null || rate.signum() <= 0 || rate.compareTo(new BigDecimal("30")) > 0)
            return Result.no("The interest rate was not reported, so the deposit was not added to the tracker. Add it there with its rate.");
        if (t.getMaturityDate() == null || !t.getMaturityDate().isAfter(t.getTransactionDate()))
            return Result.no("The maturity date was not reported, so the deposit was not added to the tracker. Add it there with its dates.");
        for (FixedDeposit f : repo.findByUserIdOrderByCreatedAtDesc(t.getUserId()))
            if (f.getBank() != null && f.getBank().equalsIgnoreCase(bank) && f.getPrincipal().compareTo(principal) == 0
                && t.getTransactionDate().equals(f.getStartDate()))
                return record(t, "fd:" + f.getId(), "That deposit is already in your tracker, so it was linked instead of added again.");
        var user = ur.findById(t.getUserId()).orElse(null);
        if (user == null) return Result.no("The user could not be found.");
        FdRequest req = new FdRequest();
        req.setBank(bank); req.setPrincipal(principal); req.setRate(rate);
        req.setStartDate(t.getTransactionDate()); req.setMaturityDate(t.getMaturityDate());
        req.setProvenance(Provenance.builder().sourceFingerprint("canonical-" + t.getId()).extractionMethod("CANONICAL_LEDGER")
            .sourceReference("canonical-" + t.getId()).extractionConfidence(t.getConfidence()).build());
        try {
            Long id = isolated().execute(st -> ts.addFd(t.getUserId(), req, user).getId());
            return record(t, "fd:" + id, "Added to your deposit tracker.");
        } catch (IllegalArgumentException e) {
            return Result.no("Not added to the tracker: " + e.getMessage());
        }
    }

    /** A deposit paid out: closes the one active deposit at that bank it can be tied to, else says why not. */
    private Result applyFdClose(CanonicalTransaction t) {
        TrackingService ts = tracking.getIfAvailable();
        FixedDepositRepository repo = fdRepo.getIfAvailable();
        if (ts == null || repo == null) return Result.no("The deposit tracker is not available.");
        String bank = institutionOf(t);
        BigDecimal paid = t.getNetAmount() != null ? t.getNetAmount() : t.getGrossAmount();
        if (bank == null) return Result.no("The bank is not known, so no deposit was closed.");
        if (paid == null || paid.signum() <= 0) return Result.no("The payout amount is not known, so no deposit was closed.");
        List<FixedDeposit> open = repo.findByUser_IdAndBankIgnoreCaseAndStatus(t.getUserId(), bank, "ACTIVE");
        if (open.size() > 1)
            open = open.stream().filter(f -> f.getMaturityDate() != null
                && Math.abs(ChronoUnit.DAYS.between(f.getMaturityDate(), t.getTransactionDate())) <= 10).toList();
        if (open.isEmpty()) return Result.no("No active deposit at " + bank + " could be tied to this payout, so none was closed.");
        if (open.size() > 1) return Result.no("More than one active deposit at " + bank + " fits this payout; close the right one in the tracker.");
        FixedDeposit fd = open.get(0);
        if (paid.compareTo(fd.getPrincipal().multiply(new BigDecimal("0.9"))) < 0)
            return Result.no("The payout is much smaller than the deposit's principal (a partial or premature payout?), so it was not closed automatically.");
        BigDecimal tds = t.getTaxes() != null && t.getTaxes().signum() > 0 ? t.getTaxes() : null;
        try {
            isolated().execute(st -> ts.closeFd(fd.getId(), t.getUserId(), paid, tds, t.getTransactionDate(), null));
        } catch (RuntimeException e) {
            return Result.no("Not closed in the tracker: " + (e.getMessage() == null ? "refused" : e.getMessage()));
        }
        return record(t, "fd:" + fd.getId(), "The deposit was closed in your tracker and its interest booked.");
    }

    private String symbolFor(PortfolioService ps, Long portfolioId, FinancialAsset a, boolean mf) {
        if (mf) {
            if (a.getIsin() != null) {
                Holding byIsin = holdingRepo.getObject().findByPortfolioIdAndIsinIgnoreCase(portfolioId, a.getIsin()).stream().findFirst().orElse(null);
                if (byIsin != null) return byIsin.getSymbol();
            }
            String base = a.getName() != null ? a.getName() : a.getSymbol();
            if (base == null) return null;
            String clean = base.toUpperCase().replaceAll("[^A-Z0-9]", "");
            if (clean.isEmpty()) return null;
            return ps.resolveFundSymbol(portfolioId, clean.substring(0, Math.min(30, clean.length())) + ".MF", a.getIsin());
        }
        String s = a.getSymbol() != null ? a.getSymbol().trim().toUpperCase() : null;
        if (s == null || s.isEmpty()) return null;
        if (s.contains(".")) return s;
        // Prefer the symbol the user's portfolio already uses (.NS or .BO); default to NSE.
        for (String suffix : new String[]{".NS", ".BO"})
            if (ps.findHoldingId(portfolioId, s + suffix) != null) return s + suffix;
        return s + ".NS";
    }
}
