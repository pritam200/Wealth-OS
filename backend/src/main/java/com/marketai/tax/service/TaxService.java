package com.marketai.tax.service;

import com.marketai.income.entity.IncomeSource;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.redemption.entity.MfRedemption;
import com.marketai.redemption.repository.MfRedemptionRepository;
import com.marketai.tax.dto.CapitalGainsExportRow;
import com.marketai.tax.dto.TaxResponse;
import com.marketai.tax.lot.CapitalGainsRates;
import com.marketai.tax.lot.FyExemptionLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives a tax picture for the Indian financial year (Apr–Mar) from recorded
 * income. Exact STCG/LTCG split and slab rates need transaction-level holding
 * periods and total-income context, so figures are clearly framed as estimates.
 */
@Service
@RequiredArgsConstructor
public class TaxService {

    private final IncomeRepository incomeRepo;
    private final MfRedemptionRepository redemptionRepo;
    private final PortfolioService portfolioService;

    public TaxResponse summary(Long userId, Integer fyStartYearArg) {
        LocalDate today = LocalDate.now();
        int fyStart = fyStartYearArg != null ? fyStartYearArg
            : (today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1);
        LocalDate from = LocalDate.of(fyStart, 4, 1);
        LocalDate to   = LocalDate.of(fyStart + 1, 3, 31);

        Map<String, BigDecimal> bySource = new HashMap<>();
        for (Object[] row : incomeRepo.sumBySource(userId, from, to)) {
            bySource.merge(IncomeSource.labelOf(row[0]), (BigDecimal) row[1], BigDecimal::add);
        }
        // Capital gains come from the disposals themselves — equity SELLs replayed from the
        // ledger, MF redemptions from MfRedemption — both signed, so a loss offsets a gain.
        // "Capital Gain" income rows are not read: older versions wrote them unsigned (a loss
        // stored as a gain) and skipped them for MF redemptions, so they are neither complete
        // nor correct.
        List<MfRedemption> mfInYear = redemptionRepo.findByUserIdOrderByRedemptionDateDesc(userId).stream()
            .filter(r -> r.getRedemptionDate() != null
                && !r.getRedemptionDate().isBefore(from) && !r.getRedemptionDate().isAfter(to)
                && r.getCapitalGain() != null)
            .toList();
        BigDecimal mfLt = mfInYear.stream().filter(r -> "LTCG".equals(r.getGainType()))
            .map(MfRedemption::getCapitalGain).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mfSt = mfInYear.stream().filter(r -> !"LTCG".equals(r.getGainType()))
            .map(MfRedemption::getCapitalGain).reduce(BigDecimal.ZERO, BigDecimal::add);
        PortfolioService.RealisedEquity equity = portfolioService.realisedEquity(userId, from, to);

        // Set-off, as the Act applies it within a year: a short-term loss reduces long-term
        // gains too; a long-term loss reduces only long-term gains (the rest carries forward).
        BigDecimal st = equity.shortTerm().add(mfSt);
        BigDecimal lt = equity.longTerm().add(mfLt);
        if (st.signum() < 0) { lt = lt.add(st); st = BigDecimal.ZERO; }
        if (lt.signum() < 0) lt = BigDecimal.ZERO;
        BigDecimal cg = st.add(lt);

        BigDecimal legacyCg = bySource.getOrDefault(IncomeSource.CAPITAL_GAIN.getLabel(), BigDecimal.ZERO);
        BigDecimal div = bySource.getOrDefault("Dividend", BigDecimal.ZERO);
        BigDecimal intr= bySource.getOrDefault("Interest", BigDecimal.ZERO);
        BigDecimal sal = bySource.getOrDefault("Salary", BigDecimal.ZERO);

        BigDecimal totalInv = cg.add(div).add(intr);

        // Capital-gains tax is exact given the lots: short-term at the STCG rate, long-term at
        // the LTCG rate above the yearly exemption — the same rate table every other screen uses.
        // Only dividends and interest are a range, because they are taxed at the user's slab.
        BigDecimal cgTax = st.multiply(CapitalGainsRates.STCG_RATE)
            .add(lt.subtract(CapitalGainsRates.LTCG_EXEMPTION).max(BigDecimal.ZERO).multiply(CapitalGainsRates.LTCG_RATE));
        BigDecimal low  = cgTax.add(div.add(intr).multiply(new BigDecimal("0.10")));
        BigDecimal high = cgTax.add(div.add(intr).multiply(new BigDecimal("0.30")));

        List<String> notes = new ArrayList<>();
        notes.add(String.format("Realised capital gains: short-term ₹%s taxed at %s%%, long-term ₹%s taxed at %s%% above the ₹1.25L exemption. "
            + "Equity sales are matched to purchases first-in-first-out; losses are set off as the Act allows.",
            strip(st), CapitalGainsRates.STCG_RATE.movePointRight(2).stripTrailingZeros().toPlainString(),
            strip(lt), CapitalGainsRates.LTCG_RATE.movePointRight(2).stripTrailingZeros().toPlainString()));
        if (equity.unmatchedUnits().signum() > 0) {
            notes.add("Some recorded sales have no matching purchase in the history (" + strip(equity.unmatchedUnits())
                + " units), so their gain is not included. Add the missing purchase for a complete figure.");
        }
        BigDecimal tds = incomeRepo.sumTds(userId, from, to);
        if (tds == null) tds = BigDecimal.ZERO;
        notes.add("Dividends and FD/RD interest are added to your income at their gross amount and taxed at your slab."
            + (tds.signum() > 0 ? " ₹" + strip(tds) + " of TDS is recorded against them; that part of the tax is already paid."
                : " Any TDS on them is already paid; it is shown here only where the advice stated it."));
        notes.add("Salary shown for context (₹" + strip(sal) + ") — TDS handled by your employer.");
        if (legacyCg.signum() != 0) {
            notes.add("Capital-gain income entries of ₹" + strip(legacyCg) + " from older sales are not counted: "
                + "gains are now taken from the sale records, and those entries did not keep the sign of a loss.");
        }
        notes.add("This is an estimate range, not tax advice. Consult a CA for filing.");

        return TaxResponse.builder()
            .fyLabel("FY " + fyStart + "-" + String.valueOf(fyStart + 1).substring(2))
            .capitalGains(cg).shortTermGains(st).longTermGains(lt).dividendIncome(div).interestIncome(intr).salaryIncome(sal)
            .totalTaxableInvestmentIncome(totalInv)
            .estimatedTaxLow(low.setScale(0, RoundingMode.HALF_UP))
            .estimatedTaxHigh(high.setScale(0, RoundingMode.HALF_UP))
            .tdsDeducted(tds)
            .notes(notes)
            .build();
    }

    private String strip(BigDecimal v) { return v == null ? "0" : v.stripTrailingZeros().toPlainString(); }

    /**
     * Flat, per-disposal rows for an ITR-filing tool import (ClearTax/Quicko-style).
     *
     * <p>Mutual fund redemptions carry their split in {@code MfRedemption}. Equity sales are
     * matched first-in-first-out against their purchase lots, one row per lot consumed, so each
     * row has a real acquisition date and gain type.
     *
     * <p>Exemption applied is recomputed row-by-row via {@link FyExemptionLedger}, in
     * redemption order, mirroring the same running-balance rule {@code RedemptionService} uses
     * when it books each redemption's tax — the ₹1,25,000 relief is a per-FY aggregate, not a
     * fresh allowance per sale.
     */
    public List<CapitalGainsExportRow> capitalGainsExport(Long userId, Integer fyStartYearArg) {
        LocalDate today = LocalDate.now();
        int fyStart = fyStartYearArg != null ? fyStartYearArg : FyExemptionLedger.fyStartYearFor(today);
        LocalDate from = LocalDate.of(fyStart, 4, 1);
        LocalDate to = LocalDate.of(fyStart + 1, 3, 31);

        List<MfRedemption> redemptions = redemptionRepo.findByUserIdOrderByRedemptionDateDesc(userId).stream()
            .filter(r -> r.getRedemptionDate() != null
                && !r.getRedemptionDate().isBefore(from) && !r.getRedemptionDate().isAfter(to))
            .sorted(Comparator.comparing(MfRedemption::getRedemptionDate))
            .toList();

        // Equity sales, one row per purchase lot each sale consumed, merged with MF redemptions
        // in date order so the exemption is used up in the order the gains were realised.
        record Pending(LocalDate date, CapitalGainsExportRow row, boolean lt, BigDecimal gain) {}
        List<Pending> pending = new ArrayList<>();
        for (PortfolioService.EquityDisposal e : portfolioService.realisedEquity(userId, from, to).disposals()) {
            var d = e.disposal();
            pending.add(new Pending(d.soldOn(), new CapitalGainsExportRow(e.symbol(), e.name(), e.isin(),
                d.acquiredOn(), d.soldOn(), d.units(), d.cost().setScale(2, RoundingMode.HALF_UP),
                d.proceeds().setScale(2, RoundingMode.HALF_UP), d.longTerm() ? "LTCG" : "STCG",
                d.gain().setScale(2, RoundingMode.HALF_UP), BigDecimal.ZERO), d.longTerm(), d.gain()));
        }

        for (MfRedemption r : redemptions) {
            BigDecimal gain = r.getCapitalGain() == null ? BigDecimal.ZERO : r.getCapitalGain();
            LocalDate acquiredOn = r.getHoldingPeriodDays() != null
                ? r.getRedemptionDate().minusDays(r.getHoldingPeriodDays()) : null;
            pending.add(new Pending(r.getRedemptionDate(), new CapitalGainsExportRow(
                r.getSymbol(), r.getFundName(), null,
                acquiredOn, r.getRedemptionDate(),
                r.getUnitsRedeemed(), r.getInvestedValueAtRedemption(), r.getRedeemedAmount(),
                r.getGainType(), gain, BigDecimal.ZERO), "LTCG".equals(r.getGainType()), gain));
        }

        FyExemptionLedger ledger = FyExemptionLedger.empty(from);
        pending.sort(Comparator.comparing(Pending::date)); // stable: same-day rows keep their order
        List<CapitalGainsExportRow> out = new ArrayList<>();
        for (Pending p : pending) {
            BigDecimal exemptionUsed = BigDecimal.ZERO;
            if (p.lt() && p.gain().signum() > 0) {
                exemptionUsed = p.gain().min(ledger.remainingExemption());
                ledger = ledger.plus(p.gain());
            }
            CapitalGainsExportRow r = p.row();
            out.add(new CapitalGainsExportRow(r.assetSymbol(), r.assetName(), r.isin(), r.acquisitionDate(),
                r.saleDate(), r.quantity(), r.acquisitionValue(), r.saleValue(), r.gainType(), r.gainOrLoss(),
                exemptionUsed.setScale(2, RoundingMode.HALF_UP)));
        }
        return out;
    }
}
