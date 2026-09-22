package com.marketai.planner.service;

import com.marketai.planner.entity.PlanCategory;
import com.marketai.planner.repository.PlanCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Buckets an {@code Expense} into one of the household plan's fine-grained categories (Groceries,
 * Petrol/Fuel, Meat/Non-Veg, ...) — a finer taxonomy than the coarse {@code ExpenseCategory} enum
 * (FOOD/SHOPPING/BILLS/...) that Gmail import already assigns. Deliberately NOT wired into the
 * Gmail ingestion pipeline itself: it runs read-time, over whatever merchant/description text an
 * {@code Expense} row already has (however it got there — manual entry, any existing or future
 * parser), so adding this planner never required touching {@code ParsedEmailImporter} or
 * re-running the existing, already-tested dedup/classification pipeline.
 *
 * <p>Deterministic keyword matching only, same spirit as {@code SpendCategorizer} — no AI call,
 * so it is free to run on every expense on every plan load. Rules live on {@link PlanCategory}
 * rows (user-editable), not in this class, so an unusual merchant can be taught without a
 * deploy — this class only implements the matching algorithm, never the vocabulary.
 */
@Component
@RequiredArgsConstructor
public class PlanCategoryClassifier {

    private final PlanCategoryRepository categoryRepository;

    /** @return the best-matching category's key, or the fallback (Miscellaneous) category's key
     *          if nothing matches, or {@code null} if the user has no categories at all yet. */
    public String classify(Long userId, String merchant, String description) {
        List<PlanCategory> categories = categoryRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(userId);
        return classify(categories, merchant, description);
    }

    /** Overload for callers that already loaded the category list (avoids refetching per expense
     *  when classifying a whole month's worth at once). */
    public String classify(List<PlanCategory> categories, String merchant, String description) {
        String text = ((merchant == null ? "" : merchant) + " " + (description == null ? "" : description))
            .toLowerCase(Locale.ROOT);

        String fallbackKey = null;
        for (PlanCategory cat : categories) {
            if (cat.isFallback()) fallbackKey = cat.getKey();
            if (cat.getKeywords() == null || cat.getKeywords().isBlank()) continue;
            for (String kw : cat.getKeywords().split(",")) {
                String trimmed = kw.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && text.contains(trimmed)) {
                    return cat.getKey();
                }
            }
        }
        return fallbackKey;
    }
}
