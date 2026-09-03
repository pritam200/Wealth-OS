package com.marketai.tax.service;

import com.marketai.income.repository.IncomeRepository;
import com.marketai.tax.dto.TaxResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
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

    public TaxResponse summary(Long userId, Integer fyStartYearArg) {
        LocalDate today = LocalDate.now();
        int fyStart = fyStartYearArg != null ? fyStartYearArg
            : (today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1);
        LocalDate from = LocalDate.of(fyStart, 4, 1);
        LocalDate to   = LocalDate.of(fyStart + 1, 3, 31);

        Map<String, BigDecimal> bySource = new HashMap<>();
        for (Object[] row : incomeRepo.sumBySource(userId, from, to)) {
            bySource.put((String) row[0], (BigDecimal) row[1]);
        }
        BigDecimal cg  = bySource.getOrDefault("Capital Gain", BigDecimal.ZERO);
        BigDecimal div = bySource.getOrDefault("Dividend", BigDecimal.ZERO);
        BigDecimal intr= bySource.getOrDefault("Interest", BigDecimal.ZERO);
        BigDecimal sal = bySource.getOrDefault("Salary", BigDecimal.ZERO);

        BigDecimal totalInv = cg.add(div).add(intr);

        // Estimate range: low = LTCG-style (12.5% on CG above ₹1.25L) + 10% on div/interest (TDS-ish floor)
        //                 high = STCG-style (20% on CG) + 30% slab on div/interest
        BigDecimal cgLtcgTaxable = cg.subtract(new BigDecimal("125000")).max(BigDecimal.ZERO);
        BigDecimal low  = cgLtcgTaxable.multiply(new BigDecimal("0.125"))
            .add(div.add(intr).multiply(new BigDecimal("0.10")));
        BigDecimal high = cg.multiply(new BigDecimal("0.20"))
            .add(div.add(intr).multiply(new BigDecimal("0.30")));

        List<String> notes = new ArrayList<>();
        notes.add("Realised capital gains: equity STCG is 20% and LTCG 12.5% above ₹1.25L exemption (rates from Jul 2024) — exact split needs each holding's buy/sell dates.");
        notes.add("Dividends and FD/RD interest are added to your income and taxed at your slab; TDS may already be deducted.");
        notes.add("Salary shown for context (₹" + strip(sal) + ") — TDS handled by your employer.");
        notes.add("This is an estimate range, not tax advice. Consult a CA for filing.");

        return TaxResponse.builder()
            .fyLabel("FY " + fyStart + "-" + String.valueOf(fyStart + 1).substring(2))
            .capitalGains(cg).dividendIncome(div).interestIncome(intr).salaryIncome(sal)
            .totalTaxableInvestmentIncome(totalInv)
            .estimatedTaxLow(low.setScale(0, RoundingMode.HALF_UP))
            .estimatedTaxHigh(high.setScale(0, RoundingMode.HALF_UP))
            .notes(notes)
            .build();
    }

    private String strip(BigDecimal v) { return v == null ? "0" : v.stripTrailingZeros().toPlainString(); }
}
