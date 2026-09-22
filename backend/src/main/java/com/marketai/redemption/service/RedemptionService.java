package com.marketai.redemption.service;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.redemption.dto.DeploymentPlan;
import com.marketai.redemption.entity.MfRedemption;
import com.marketai.redemption.entity.Reinvestment;
import com.marketai.redemption.repository.MfRedemptionRepository;
import com.marketai.tax.lot.CapitalGainsRates;
import com.marketai.tax.lot.FyExemptionLedger;
import com.marketai.technical.dto.TechnicalAnalysisDto;
import com.marketai.technical.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedemptionService {

    private static final String NIFTY_SYMBOL = "^NSEI";

    private final MfRedemptionRepository redemptionRepo;
    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final TechnicalIndicatorService technicalIndicatorService;

    /**
     * Called from PortfolioService.sellHolding when the sold symbol is a mutual fund, in
     * addition to (not instead of) the existing Income record — this is what gives a
     * redemption a persistent lifecycle instead of just vanishing on delete.
     */
    @Transactional
    public MfRedemption recordRedemption(Long userId, Holding holding, BigDecimal unitsRedeemed, BigDecimal nav) {
        BigDecimal redeemedAmount = nav.multiply(unitsRedeemed).setScale(2, RoundingMode.HALF_UP);
        BigDecimal investedPortion = holding.getAverageCost().multiply(unitsRedeemed).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gain = redeemedAmount.subtract(investedPortion);

        LocalDate today = LocalDate.now();
        long daysHeld = holding.getBuyDate() != null ? ChronoUnit.DAYS.between(holding.getBuyDate(), today) : 0;
        boolean isLongTerm = daysHeld >= 365;
        String gainType = isLongTerm ? "LTCG" : "STCG";
        BigDecimal tax = estimateTax(userId, gain, isLongTerm, today);

        MfRedemption redemption = MfRedemption.builder()
            .userId(userId).symbol(holding.getSymbol()).fundName(holding.getName())
            .unitsRedeemed(unitsRedeemed).navAtRedemption(nav).redeemedAmount(redeemedAmount)
            .investedValueAtRedemption(investedPortion).redemptionDate(today)
            .holdingPeriodDays(daysHeld).gainType(gainType).capitalGain(gain).estimatedTax(tax)
            .reinvestedAmount(BigDecimal.ZERO).status("ACTIVE")
            .build();
        return redemptionRepo.save(redemption);
    }

    /**
     * Estimated tax on this redemption's gain.
     *
     * <p>Two rules this gets right that the previous local constants did not. The short-term rate
     * is 20%, not 15% — raised by the Finance (No.2) Act 2024 — and the rates now come from
     * {@link CapitalGainsRates}, the one place they are maintained, rather than being duplicated
     * here where they went stale. And the ₹1,25,000 long-term exemption is consumed from a
     * running financial-year balance via {@link FyExemptionLedger}: applying it afresh to every
     * redemption reported ₹0 tax on four ₹1,20,000 gains in one year, where the real figure is
     * 12.5% of (₹4,80,000 − ₹1,25,000).
     */
    private BigDecimal estimateTax(Long userId, BigDecimal gain, boolean isLongTerm, LocalDate asOf) {
        if (gain == null || gain.signum() <= 0) return BigDecimal.ZERO;
        if (!isLongTerm) {
            return gain.multiply(CapitalGainsRates.STCG_RATE).setScale(2, RoundingMode.HALF_UP);
        }
        int fyStartYear = FyExemptionLedger.fyStartYearFor(asOf);
        LocalDate fyStart = LocalDate.of(fyStartYear, 4, 1);
        LocalDate fyEnd = LocalDate.of(fyStartYear + 1, 3, 31);
        BigDecimal alreadyRealised = redemptionRepo.sumLongTermGainsInFy(userId, fyStart, fyEnd);
        FyExemptionLedger ledger = new FyExemptionLedger(fyStartYear,
            alreadyRealised != null ? alreadyRealised : BigDecimal.ZERO);
        BigDecimal taxable = gain.subtract(ledger.remainingExemption()).max(BigDecimal.ZERO);
        return taxable.multiply(CapitalGainsRates.LTCG_RATE).setScale(2, RoundingMode.HALF_UP);
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
     * "after a correction" trigger is a real number derived from Nifty 50's own ATR-based
     * expected move (same formula ForecastService uses), not a vague "wait for a dip".
     */
    public DeploymentPlan getDeploymentPlan(Long userId, Long redemptionId) {
        MfRedemption r = redemptionRepo.findById(redemptionId)
            .filter(x -> x.getUserId().equals(userId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Redemption not found"));
        BigDecimal cash = r.getCashRemaining();

        BigDecimal now = cash.multiply(BigDecimal.valueOf(0.20)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal afterCorrection = cash.multiply(BigDecimal.valueOf(0.30)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal viaSip = cash.subtract(now).subtract(afterCorrection);

        String trigger = "market data unavailable — using a generic 5% pullback as the trigger";
        try {
            TechnicalAnalysisDto ta = technicalIndicatorService.analyse(NIFTY_SYMBOL);
            if (ta.getPrice() != null && ta.getAtr() != null) {
                double price = ta.getPrice().doubleValue();
                double sigma = ta.getAtr().doubleValue() * Math.sqrt(21); // ~1-month expected move
                double level = price - sigma;
                trigger = String.format("Nifty 50 below %.0f (a %.1f%% pullback from today's %.0f)", level, sigma / price * 100, price);
            }
        } catch (Exception e) {
            log.debug("Could not compute correction trigger: {}", e.getMessage());
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
                for (Holding h : holdingRepository.findByPortfolioId(p.getId())) {
                    if (h.getSymbol() != null && h.getSymbol().endsWith(".MF") && !h.getSymbol().equals(excludeSymbol)) {
                        names.add(h.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not list existing MF holdings: {}", e.getMessage());
        }
        return names;
    }
}
