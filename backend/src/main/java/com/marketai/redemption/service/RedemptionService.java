package com.marketai.redemption.service;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.redemption.dto.DeploymentPlan;
import com.marketai.redemption.entity.MfRedemption;
import com.marketai.redemption.entity.Reinvestment;
import com.marketai.redemption.repository.MfRedemptionRepository;
import com.marketai.tax.lot.CapitalGainsRates;
import com.marketai.tax.lot.FifoLedger;
import com.marketai.tax.lot.FyExemptionLedger;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedemptionService {

    private static final String NIFTY_SYMBOL = "^NSEI";

    private final MfRedemptionRepository redemptionRepo;
    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final TechnicalIndicatorService technicalIndicatorService;

    /**
     * Called from PortfolioService.sellHolding when the sold symbol is a mutual fund. This is
     * the one realized-gain record for a redemption.
     *
     * <p>Tax classification and cost come from a FIFO replay of the holding's ledger — the same
     * statutory matching equity sales use — not from the position's weighted-average cost and a
     * single first-buy date. A SIP redeemed in one go therefore splits correctly into short-term
     * and long-term parcels. {@code costBasisFallback} is used only when the ledger cannot explain
     * the sale (no purchase rows, or unmatched units).
     *
     * @param saleTransactionId the SELL row just written; its FIFO disposals are what this books
     */
    @Transactional
    public MfRedemption recordRedemption(Long userId, Holding holding, BigDecimal unitsRedeemed, BigDecimal nav,
                                         BigDecimal costBasisFallback, LocalDate redemptionDate,
                                         Long saleTransactionId) {
        LocalDate today = redemptionDate;
        BigDecimal redeemedAmount = nav.multiply(unitsRedeemed).setScale(2, RoundingMode.HALF_UP);

        FifoSplit split = fifoSplit(holding, saleTransactionId, unitsRedeemed, nav, costBasisFallback, today);
        BigDecimal gain = split.stGain.add(split.ltGain);
        String gainType = split.gainType();
        BigDecimal tax = estimateTax(userId, split.stGain, split.ltGain, today);

        MfRedemption redemption = MfRedemption.builder()
            .userId(userId).symbol(holding.getSymbol()).fundName(holding.getName())
            .unitsRedeemed(unitsRedeemed).navAtRedemption(nav).redeemedAmount(redeemedAmount)
            .investedValueAtRedemption(split.cost).redemptionDate(today)
            .holdingPeriodDays(split.holdingPeriodDays).gainType(gainType)
            .capitalGain(gain).shortTermGain(split.stGain).longTermGain(split.ltGain)
            .estimatedTax(tax)
            .reinvestedAmount(BigDecimal.ZERO).status("ACTIVE")
            .build();
        return redemptionRepo.save(redemption);
    }

    /** Compatibility for callers/tests that do not have a sale transaction id. */
    @Transactional
    public MfRedemption recordRedemption(Long userId, Holding holding, BigDecimal unitsRedeemed, BigDecimal nav,
                                         BigDecimal costBasis, LocalDate redemptionDate) {
        return recordRedemption(userId, holding, unitsRedeemed, nav, costBasis, redemptionDate, null);
    }

    private record FifoSplit(BigDecimal cost, BigDecimal stGain, BigDecimal ltGain, Long holdingPeriodDays) {
        String gainType() {
            boolean st = stGain != null && stGain.signum() != 0;
            boolean lt = ltGain != null && ltGain.signum() != 0;
            if (st && lt) return "MIXED";
            if (lt) return "LTCG";
            return "STCG";
        }
    }

    private FifoSplit fifoSplit(Holding holding, Long saleTransactionId, BigDecimal unitsRedeemed,
                                BigDecimal nav, BigDecimal costBasisFallback, LocalDate redemptionDate) {
        if (holding.getId() == null) {
            return averageCostFallback(holding, unitsRedeemed, nav, costBasisFallback, redemptionDate);
        }
        List<Transaction> ledger = transactionRepository
            .findByHoldingIdOrderByTransactionDateAscIdAsc(holding.getId());
        if (ledger.isEmpty()) {
            return averageCostFallback(holding, unitsRedeemed, nav, costBasisFallback, redemptionDate);
        }
        List<FifoLedger.Trade> trades = ledger.stream().map(FifoLedger.Trade::of).toList();
        FifoLedger.Result r = FifoLedger.replay(trades);

        List<FifoLedger.Disposal> mine = r.disposals().stream()
            .filter(d -> saleTransactionId == null
                ? d.soldOn().equals(redemptionDate)
                : ("TXN-" + saleTransactionId).equals(d.saleId()))
            .toList();
        // Same-day sales without an id: take the last matching unitsRedeemed worth of disposals.
        if (saleTransactionId == null && !mine.isEmpty()) {
            BigDecimal need = unitsRedeemed;
            List<FifoLedger.Disposal> take = new ArrayList<>();
            for (int i = mine.size() - 1; i >= 0 && need.signum() > 0; i--) {
                FifoLedger.Disposal d = mine.get(i);
                take.add(d);
                need = need.subtract(d.units());
            }
            mine = take;
        }
        if (mine.isEmpty() || r.unmatchedUnits().signum() > 0 && saleTransactionId != null
                && mine.stream().map(FifoLedger.Disposal::units).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .compareTo(unitsRedeemed) != 0) {
            // Ledger cannot explain this sale cleanly — do not invent lot ages.
            if (r.unmatchedUnits().signum() > 0) {
                log.warn("MF redemption {} used average-cost fallback: unmatched units {}",
                    holding.getSymbol(), r.unmatchedUnits());
            }
            return averageCostFallback(holding, unitsRedeemed, nav, costBasisFallback, redemptionDate);
        }

        BigDecimal cost = BigDecimal.ZERO, st = BigDecimal.ZERO, lt = BigDecimal.ZERO;
        LocalDate earliest = null;
        for (FifoLedger.Disposal d : mine) {
            cost = cost.add(d.cost());
            if (d.longTerm()) lt = lt.add(d.gain());
            else st = st.add(d.gain());
            if (earliest == null || d.acquiredOn().isBefore(earliest)) earliest = d.acquiredOn();
        }
        Long days = earliest != null ? ChronoUnit.DAYS.between(earliest, redemptionDate) : null;
        return new FifoSplit(cost.setScale(2, RoundingMode.HALF_UP),
            st.setScale(2, RoundingMode.HALF_UP), lt.setScale(2, RoundingMode.HALF_UP), days);
    }

    private FifoSplit averageCostFallback(Holding holding, BigDecimal unitsRedeemed, BigDecimal nav,
                                          BigDecimal costBasisFallback, LocalDate redemptionDate) {
        BigDecimal cost = costBasisFallback != null
            ? costBasisFallback.setScale(2, RoundingMode.HALF_UP)
            : holding.getAverageCost().multiply(unitsRedeemed).setScale(2, RoundingMode.HALF_UP);
        BigDecimal proceeds = nav.multiply(unitsRedeemed).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gain = proceeds.subtract(cost);
        boolean longTerm = CapitalGainsRates.isLongTerm(holding.getBuyDate(), redemptionDate);
        Long days = holding.getBuyDate() != null
            ? ChronoUnit.DAYS.between(holding.getBuyDate(), redemptionDate) : null;
        return longTerm
            ? new FifoSplit(cost, BigDecimal.ZERO.setScale(2), gain.setScale(2, RoundingMode.HALF_UP), days)
            : new FifoSplit(cost, gain.setScale(2, RoundingMode.HALF_UP), BigDecimal.ZERO.setScale(2), days);
    }

    /**
     * Estimated tax on this redemption's gains, including the 4% cess. Short-term and long-term
     * parcels are taxed separately; the ₹1,25,000 long-term exemption is consumed from a running
     * financial-year balance via {@link FyExemptionLedger}.
     */
    private BigDecimal estimateTax(Long userId, BigDecimal stGain, BigDecimal ltGain, LocalDate asOf) {
        BigDecimal st = stGain == null ? BigDecimal.ZERO : stGain.max(BigDecimal.ZERO);
        BigDecimal lt = ltGain == null ? BigDecimal.ZERO : ltGain.max(BigDecimal.ZERO);
        BigDecimal stTax = st.multiply(CapitalGainsRates.STCG_RATE);

        int fyStartYear = FyExemptionLedger.fyStartYearFor(asOf);
        LocalDate fyStart = LocalDate.of(fyStartYear, 4, 1);
        LocalDate fyEnd = LocalDate.of(fyStartYear + 1, 3, 31);
        BigDecimal alreadyRealised = redemptionRepo.sumLongTermGainsInFy(userId, fyStart, fyEnd);
        FyExemptionLedger ledger = new FyExemptionLedger(fyStartYear,
            alreadyRealised != null ? alreadyRealised : BigDecimal.ZERO);
        BigDecimal taxableLt = lt.subtract(ledger.remainingExemption()).max(BigDecimal.ZERO);
        BigDecimal ltTax = taxableLt.multiply(CapitalGainsRates.LTCG_RATE);

        return CapitalGainsRates.withCess(stTax.add(ltTax));
    }

    /**
     * Total redemption proceeds not yet redeployed, across all open redemptions.
     *
     * IMPORTANT: this is an *earmark on cash the user already holds*, not extra cash. When a
     * fund is redeemed the money lands in a bank account, so it is already counted in
     * CashAccount balances. Adding this figure to tracked cash would double-count it — it
     * answers "how much of my cash came from a redemption and was meant to be reinvested",
     * never "how much cash do I have".
     */
    public BigDecimal totalAwaitingRedeployment(Long userId) {
        BigDecimal total = BigDecimal.ZERO;
        for (MfRedemption r : redemptionRepo.findByUserIdAndStatus(userId, "ACTIVE")) {
            total = total.add(r.getCashRemaining());
        }
        return total;
    }

    public List<MfRedemption> list(Long userId) {
        return redemptionRepo.findByUserIdOrderByRedemptionDateDesc(userId);
    }

    @Transactional
    public MfRedemption recordReinvestment(Long userId, Long redemptionId, BigDecimal amount, LocalDate date, String targetFund, String note) {
        MfRedemption r = redemptionRepo.findById(redemptionId)
            .filter(x -> x.getUserId().equals(userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Redemption not found"));

        Reinvestment tranche = Reinvestment.builder()
            .redemption(r).amount(amount).date(date != null ? date : LocalDate.now())
            .targetFund(targetFund).note(note).build();
        r.getReinvestments().add(tranche);
        r.setReinvestedAmount(r.getReinvestedAmount().add(amount));
        if (r.getReinvestedAmount().compareTo(r.getRedeemedAmount()) >= 0) r.setStatus("COMPLETED");
        return redemptionRepo.save(r);
    }

    /**
     * Rule-based staged deployment plan for the remaining cash — NOT continuous AI market
     * monitoring (out of scope for this v1; see class-level note in DeploymentPlan). The
     * "after a correction" trigger is a real number derived from Nifty 50's realised
     * volatility (VolatilityModel, the same model ForecastService uses), not a vague "wait for a dip".
     */
    public DeploymentPlan getDeploymentPlan(Long userId, Long redemptionId) {
        MfRedemption r = redemptionRepo.findById(redemptionId)
            .filter(x -> x.getUserId().equals(userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Redemption not found"));
        BigDecimal cash = r.getCashRemaining();

        BigDecimal now = cash.multiply(BigDecimal.valueOf(0.20)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal afterCorrection = cash.multiply(BigDecimal.valueOf(0.30)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal viaSip = cash.subtract(now).subtract(afterCorrection);

        String trigger = "Nifty 50 volatility unavailable — no pullback level is set; deploy this tranche by SIP instead.";
        try {
            String t = com.marketai.forecast.model.VolatilityModel.pullbackTrigger(technicalIndicatorService.analyse(NIFTY_SYMBOL), 20);
            if (t != null) trigger = t;
        } catch (Exception e) {
            log.debug("Could not compute pullback trigger: {}", e.getMessage());
        }

        List<DeploymentPlan.Tranche> tranches = new ArrayList<>();
        tranches.add(DeploymentPlan.Tranche.builder().label("Invest now").amount(now).percentOfTotal(20.0).trigger("Immediate").build());
        tranches.add(DeploymentPlan.Tranche.builder().label("After a correction").amount(afterCorrection).percentOfTotal(30.0).trigger(trigger).build());
        tranches.add(DeploymentPlan.Tranche.builder().label("Monthly SIP over 6 months").amount(viaSip).percentOfTotal(50.0).trigger("~" + viaSip.divide(BigDecimal.valueOf(6), 2, RoundingMode.HALF_UP) + "/month for 6 months").build());

        List<String> suitableFunds = suitableFundsFor(userId, r.getSymbol());

        return DeploymentPlan.builder()
            .tranches(tranches)
            .suitableFunds(suitableFunds)
            .basis("Rule-based staged plan (20% now / 30% on a pullback / 50% via SIP) grounded in Nifty 50's own volatility — not continuous live market monitoring. Suitable funds are limited to ones already in your portfolio. Not investment advice.")
            .build();
    }

    /** Simplification, disclosed in the plan's `basis`: no fund-category/metadata database
     *  exists to recommend external funds, so suggestions are limited to the user's own
     *  existing mutual fund holdings (excluding the one just redeemed). */
    private List<String> suitableFundsFor(Long userId, String excludeSymbol) {
        List<String> names = new ArrayList<>();
        try {
            for (Portfolio p : portfolioRepository.findByUserId(userId)) {
                for (Holding h : holdingRepository.findOpenByPortfolioId(p.getId())) {
                    if (h.getSymbol() != null && h.getSymbol().endsWith(".MF") && !h.getSymbol().equals(excludeSymbol)) {
                        names.add(h.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not list existing MF holdings: {}", e.getMessage());
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }
}
