package com.marketai.card.optimizer;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Rolls recent spend up by category so card recommendations rest on what the user actually
 * spends rather than on a hypothetical profile.
 *
 * On terminology: these are INFERRED categories, not Merchant Category Codes. Real MCCs come
 * from the card network's authorization feed; bank and card emails carry only a merchant
 * string. Calling the result an MCC would overstate what we know, so the coverage figure below
 * reports how much spend could be categorized at all — a recommendation built on 30% coverage
 * deserves far less weight than one built on 90%, and hiding that would be the whole failure.
 */
@Service
@RequiredArgsConstructor
public class SpendAggregator {

    private static final int WINDOW_DAYS = 90;

    @Data @Builder
    public static class CategorySpend {
        private String category;
        private BigDecimal observedAmount;
        private BigDecimal projectedAnnual;
        private int txnCount;
        /** Always true — see the class note on MCCs. */
        private boolean inferred;
    }

    @Data @Builder
    public static class SpendProfile {
        private int days;
        private BigDecimal observedSpend;
        private BigDecimal projectedAnnualSpend;
        private String annualizationBasis;
        /** 0..1 — share of spend with a usable category. */
        private BigDecimal coverage;
        private List<CategorySpend> byCategory;
        private String note;
    }

    private final ExpenseRepository expenseRepository;

    public SpendProfile aggregate(Long userId) {
        LocalDate to = LocalDate.now();
        // WINDOW_DAYS - 1: the repository query is inclusive at both ends, so minusDays(90)
        // actually spans 91 days while the annualisation below scales by 365/90 — overstating
        // every projected annual spend by ~1.1%.
        LocalDate from = to.minusDays(WINDOW_DAYS - 1);

        List<Expense> expenses =
            expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, from, to);

        Map<String, BigDecimal> byCat = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal categorized = BigDecimal.ZERO;

        for (Expense e : expenses) {
            BigDecimal amt = e.getAmount();
            if (amt == null || amt.compareTo(BigDecimal.ZERO) <= 0) continue;
            total = total.add(amt);

            ExpenseCategory cat = e.getCategory();
            // UNCATEGORIZED is also where legacy free-text values land, so it genuinely means
            // "we don't know" rather than "miscellaneous spend".
            boolean usable = cat != null && cat != ExpenseCategory.UNCATEGORIZED;
            String key = usable ? cat.getLabel() : ExpenseCategory.UNCATEGORIZED.getLabel();
            if (usable) categorized = categorized.add(amt);

            byCat.merge(key, amt, BigDecimal::add);
            counts.merge(key, 1, Integer::sum);
        }

        // 365/90 — a flat scale-up. Explicitly NOT seasonally adjusted: three months of data
        // cannot separate a festival-season spike from a baseline, and pretending otherwise
        // would inflate every projection built on it.
        BigDecimal factor = new BigDecimal("365").divide(new BigDecimal(WINDOW_DAYS), 4, RoundingMode.HALF_UP);

        List<CategorySpend> cats = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : byCat.entrySet()) {
            cats.add(CategorySpend.builder()
                .category(e.getKey())
                .observedAmount(e.getValue().setScale(2, RoundingMode.HALF_UP))
                .projectedAnnual(e.getValue().multiply(factor).setScale(2, RoundingMode.HALF_UP))
                .txnCount(counts.getOrDefault(e.getKey(), 0))
                .inferred(true)
                .build());
        }
        cats.sort((a, b) -> b.getObservedAmount().compareTo(a.getObservedAmount()));

        BigDecimal coverage = total.compareTo(BigDecimal.ZERO) > 0
            ? categorized.divide(total, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        String note;
        if (expenses.isEmpty()) {
            note = "No expenses recorded in the last " + WINDOW_DAYS + " days — recommendations cannot be personalised.";
        } else if (coverage.compareTo(new BigDecimal("0.5")) < 0) {
            note = "Only " + coverage.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP)
                 + "% of spend has a usable category, so these projections are weakly grounded.";
        } else {
            note = "Categories are inferred from merchant names, not from card-network MCCs.";
        }

        return SpendProfile.builder()
            .days(WINDOW_DAYS)
            .observedSpend(total.setScale(2, RoundingMode.HALF_UP))
            .projectedAnnualSpend(total.multiply(factor).setScale(2, RoundingMode.HALF_UP))
            .annualizationBasis(WINDOW_DAYS + "d observed × " + factor + "; not seasonally adjusted")
            .coverage(coverage)
            .byCategory(cats)
            .note(note)
            .build();
    }
}
