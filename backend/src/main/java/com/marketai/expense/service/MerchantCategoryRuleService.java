package com.marketai.expense.service;

import com.marketai.common.ledger.PartyNames;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.entity.MerchantCategoryRule;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.expense.repository.MerchantCategoryRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Remembers the category a user picked for a merchant and applies it to that merchant's expenses. */
@Service
@RequiredArgsConstructor
public class MerchantCategoryRuleService {

    /** Shorter keys ("upi", "atm") name no merchant in particular; a rule on them would recategorise unrelated spend. */
    static final int MIN_KEY = 4;

    private final MerchantCategoryRuleRepository repo;
    private final ExpenseRepository expenses;

    static String key(String merchant) {
        String k = PartyNames.normalise(merchant);
        return k.length() >= MIN_KEY ? k : null;
    }

    /** The user's category for this merchant, if they chose one. */
    public Optional<ExpenseCategory> categoryFor(Long userId, String merchant) {
        String k = key(merchant);
        if (userId == null || k == null) return Optional.empty();
        return repo.findByUserIdAndMerchantKey(userId, k).map(MerchantCategoryRule::getCategory);
    }

    /**
     * Remembers the category for this merchant and gives it to the merchant's other expenses —
     * purchases and refunds alike, so a refund stays in the category it reduces. Returns how
     * many other expenses changed.
     */
    @Transactional
    public int remember(Long userId, String merchant, ExpenseCategory category) {
        String k = key(merchant);
        if (k == null || category == null) return 0;
        MerchantCategoryRule rule = repo.findByUserIdAndMerchantKey(userId, k)
            .orElseGet(() -> MerchantCategoryRule.builder().userId(userId).merchantKey(k).build());
        rule.setMerchant(merchant.trim());
        rule.setCategory(category);
        rule.setUpdatedAt(LocalDateTime.now());
        repo.save(rule);

        int changed = 0;
        for (Expense e : expenses.findByUserIdOrderByExpenseDateDesc(userId)) {
            if (!k.equals(key(e.getMerchant())) || e.getCategory() == category) continue;
            e.setCategory(category);
            expenses.save(e);
            changed++;
        }
        return changed;
    }

    public List<MerchantCategoryRule> list(Long userId) {
        return repo.findByUserIdOrderByMerchantAsc(userId);
    }

    /** Stops applying the rule to new expenses; expenses already recategorised keep their category. */
    @Transactional
    public boolean forget(Long userId, Long id) {
        Optional<MerchantCategoryRule> r = repo.findByIdAndUserId(id, userId);
        r.ifPresent(repo::delete);
        return r.isPresent();
    }
}
