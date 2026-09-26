package com.marketai.subscription.service;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.subscription.dto.SubscriptionCadence;
import com.marketai.subscription.dto.SubscriptionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds recurring charges in a user's already-imported/categorized expense history — same
 * grouping-by-merchant idea {@code TransactionFingerprinter} uses for dedup, applied here to spot
 * repetition instead of collapsing it. No new entity or write path: like {@code ReminderService},
 * this is a pure read-time derivation over data that already exists, so there is nothing to keep
 * in sync.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionDetectionService {

    /** Below this many same-merchant, same-cadence charges, a repeat is coincidence, not a
     *  subscription — two coffee runs a month apart don't make a subscription. */
    private static final int MIN_OCCURRENCES = 3;

    /** How far a charge amount may drift from the historical average and still count as "the
     *  same" subscription rather than a price change worth flagging. */
    private static final double AMOUNT_TOLERANCE = 0.05;

    private final ExpenseRepository expenseRepository;

    public List<SubscriptionResponse> detectSubscriptions(Long userId) {
        List<Expense> expenses = expenseRepository.findByUserIdOrderByExpenseDateAsc(userId);

        Map<String, List<Expense>> byMerchant = new LinkedHashMap<>();
        for (Expense e : expenses) {
            // ACCOUNT_TRANSFER (CRED/CC-bill settlement) and INVESTMENT-flavoured rows aren't
            // spend at all — grouping them in would flag a monthly card-bill payment as a
            // "subscription", which it isn't.
            if (e.getCategory() == ExpenseCategory.ACCOUNT_TRANSFER || e.getCategory() == ExpenseCategory.INVESTMENT) continue;
            String key = normalizedKey(e);
            if (key == null) continue;
            byMerchant.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        List<SubscriptionResponse> out = new ArrayList<>();
        for (List<Expense> group : byMerchant.values()) {
            detectForMerchant(group).ifPresent(out::add);
        }
        out.sort(Comparator.comparing(SubscriptionResponse::getLastSeenDate).reversed());
        return out;
    }

    private Optional<SubscriptionResponse> detectForMerchant(List<Expense> sortedAsc) {
        if (sortedAsc.size() < MIN_OCCURRENCES) return Optional.empty();

        // Checked most-common-first purely so a fund with weirdly-close-to-monthly weekly
        // charges doesn't get misclassified — MONTHLY's wider window is tried first.
        for (SubscriptionCadence cadence : new SubscriptionCadence[]{SubscriptionCadence.MONTHLY, SubscriptionCadence.WEEKLY, SubscriptionCadence.ANNUAL}) {
            Optional<SubscriptionResponse> found = detectForCadence(sortedAsc, cadence);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private Optional<SubscriptionResponse> detectForCadence(List<Expense> sortedAsc, SubscriptionCadence cadence) {
        List<Expense> run = longestTrailingRun(sortedAsc, cadence);
        if (run.size() < MIN_OCCURRENCES) return Optional.empty();

        BigDecimal last = run.get(run.size() - 1).getAmount();
        BigDecimal baseline = average(run.subList(0, run.size() - 1));

        // The historical charges themselves must agree with each other, or this merchant just
        // happens to recur on a cadence with unrelated amounts (e.g. ad-hoc Amazon orders) —
        // not a subscription.
        for (Expense e : run.subList(0, run.size() - 1)) {
            if (!withinTolerance(e.getAmount(), baseline)) return Optional.empty();
        }

        boolean priceIncreased = !withinTolerance(last, baseline);
        Expense latest = run.get(run.size() - 1);

        return Optional.of(SubscriptionResponse.builder()
            .merchant(displayName(latest))
            .category(latest.getCategory() != null ? latest.getCategory().getLabel() : null)
            .cadence(cadence)
            .typicalAmount(baseline.setScale(2, RoundingMode.HALF_UP))
            .currentAmount(last.setScale(2, RoundingMode.HALF_UP))
            .lastSeenDate(latest.getExpenseDate())
            .occurrenceCount(run.size())
            .priceIncreased(priceIncreased)
            .previousAmount(priceIncreased ? baseline.setScale(2, RoundingMode.HALF_UP) : null)
            .build());
    }

    /** Walks backward from the most recent charge, extending the run while each consecutive
     *  gap still falls in the given cadence's day range — an older, irregular prefix of the
     *  same merchant's history doesn't disqualify a recent, genuinely recurring run. */
    private List<Expense> longestTrailingRun(List<Expense> sortedAsc, SubscriptionCadence cadence) {
        List<Expense> run = new ArrayList<>();
        run.add(sortedAsc.get(sortedAsc.size() - 1));
        for (int i = sortedAsc.size() - 2; i >= 0; i--) {
            long gapDays = ChronoUnit.DAYS.between(sortedAsc.get(i).getExpenseDate(), sortedAsc.get(i + 1).getExpenseDate());
            if (!inRange(gapDays, cadence)) break;
            run.add(0, sortedAsc.get(i));
        }
        return run;
    }

    private boolean inRange(long gapDays, SubscriptionCadence cadence) {
        switch (cadence) {
            case WEEKLY: return gapDays >= 5 && gapDays <= 9;
            case MONTHLY: return gapDays >= 25 && gapDays <= 35;
            case ANNUAL: return gapDays >= 350 && gapDays <= 380;
            default: return false;
        }
    }

    private boolean withinTolerance(BigDecimal amount, BigDecimal baseline) {
        if (baseline.signum() == 0) return amount.signum() == 0;
        BigDecimal diff = amount.subtract(baseline).abs();
        return diff.divide(baseline, 6, RoundingMode.HALF_UP).doubleValue() <= AMOUNT_TOLERANCE;
    }

    private BigDecimal average(List<Expense> expenses) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Expense e : expenses) sum = sum.add(e.getAmount());
        return sum.divide(BigDecimal.valueOf(expenses.size()), 6, RoundingMode.HALF_UP);
    }

    private String normalizedKey(Expense e) {
        String raw = e.getMerchant() != null && !e.getMerchant().isBlank() ? e.getMerchant() : e.getDescription();
        if (raw == null || raw.isBlank()) return null;
        return raw.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String displayName(Expense e) {
        String raw = e.getMerchant() != null && !e.getMerchant().isBlank() ? e.getMerchant() : e.getDescription();
        return raw.trim();
    }
}
