package com.marketai.planner.service;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.planner.dto.PlannerDtos.*;
import com.marketai.planner.entity.MonthlyReflection;
import com.marketai.planner.entity.PlanCategory;
import com.marketai.planner.entity.PlannerSettings;
import com.marketai.planner.entity.SinkingFund;
import com.marketai.planner.entity.SinkingFundEntry;
import com.marketai.planner.repository.MonthlyReflectionRepository;
import com.marketai.planner.repository.PlanCategoryRepository;
import com.marketai.planner.repository.PlannerSettingsRepository;
import com.marketai.planner.repository.SinkingFundEntryRepository;
import com.marketai.planner.repository.SinkingFundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Turns the household budget PDF into a live plan-vs-actual view: every category's "actual" is
 * derived at read time from already-imported {@code Expense} rows (classified by
 * {@link PlanCategoryClassifier}, or the user's own override) — nothing here mutates an Expense
 * except the explicit move/override action, and nothing here ever fabricates a transaction.
 */
@Service
@RequiredArgsConstructor
public class PlannerService {

    private final PlanCategoryRepository categoryRepository;
    private final PlannerSettingsRepository settingsRepository;
    private final ExpenseRepository expenseRepository;
    private final PlanCategoryClassifier classifier;
    private final SinkingFundEntryRepository sinkingFundEntryRepository;
    private final SinkingFundRepository sinkingFundRepository;
    private final MonthlyReflectionRepository reflectionRepository;

    // ---- Category catalog -------------------------------------------------------------

    @Transactional
    public List<CategoryResponse> listCategories(Long userId) {
        ensureSeeded(userId);
        return categoryRepository.findByUserIdOrderBySortOrderAsc(userId).stream()
            .map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    void ensureSeeded(Long userId) {
        if (!categoryRepository.existsByUserId(userId)) {
            categoryRepository.saveAll(PlanCategoryDefaults.seedCategories(userId));
        }
        if (settingsRepository.findByUserId(userId).isEmpty()) {
            settingsRepository.save(PlannerSettings.builder()
                .userId(userId).monthlyLimit(PlanCategoryDefaults.DEFAULT_MONTHLY_LIMIT).build());
        }
    }

    @Transactional
    public CategoryResponse addCategory(Long userId, CategoryRequest req) {
        ensureSeeded(userId);
        String key = deriveKey(req.getName());
        if (categoryRepository.findByUserIdAndKey(userId, key).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A category with that name already exists");
        }
        int nextOrder = categoryRepository.findByUserIdOrderBySortOrderAsc(userId).stream()
            .mapToInt(c -> c.getSortOrder() == null ? 0 : c.getSortOrder()).max().orElse(0) + 1;
        PlanCategory saved = categoryRepository.save(PlanCategory.builder()
            .userId(userId).key(key).name(req.getName())
            .groupName(req.getGroupName() != null ? req.getGroupName() : "Miscellaneous")
            .plannedAmount(req.getPlannedAmount() != null ? req.getPlannedAmount() : BigDecimal.ZERO)
            .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : nextOrder)
            .keywords(req.getKeywords())
            .build());
        return toDto(saved);
    }

    @Transactional
    public CategoryResponse updateCategory(Long userId, Long id, CategoryRequest req) {
        PlanCategory cat = categoryRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        if (req.getName() != null) cat.setName(req.getName());
        if (req.getGroupName() != null) cat.setGroupName(req.getGroupName());
        if (req.getPlannedAmount() != null) cat.setPlannedAmount(req.getPlannedAmount());
        if (req.getSortOrder() != null) cat.setSortOrder(req.getSortOrder());
        if (req.getKeywords() != null) cat.setKeywords(req.getKeywords());
        if (req.getActive() != null) cat.setActive(req.getActive());
        return toDto(categoryRepository.save(cat));
    }

    @Transactional
    public void deactivateCategory(Long userId, Long id) {
        PlanCategory cat = categoryRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        // Soft delete only — a category that already has months of actuals classified against
        // it must stay resolvable, or every past month's plan view would break.
        cat.setActive(false);
        categoryRepository.save(cat);
    }

    // ---- Settings ----------------------------------------------------------------------

    @Transactional
    public SettingsResponse getSettings(Long userId) {
        ensureSeeded(userId);
        PlannerSettings s = settingsRepository.findByUserId(userId).orElseThrow();
        return SettingsResponse.builder().monthlyLimit(s.getMonthlyLimit()).build();
    }

    @Transactional
    public SettingsResponse updateSettings(Long userId, SettingsRequest req) {
        ensureSeeded(userId);
        PlannerSettings s = settingsRepository.findByUserId(userId).orElseThrow();
        if (req.getMonthlyLimit() != null) s.setMonthlyLimit(req.getMonthlyLimit());
        s.setUpdatedAt(java.time.LocalDateTime.now());
        settingsRepository.save(s);
        return SettingsResponse.builder().monthlyLimit(s.getMonthlyLimit()).build();
    }

    // ---- Monthly plan-vs-actual ---------------------------------------------------------

    @Transactional
    public MonthlyPlanResponse getMonthlyPlan(Long userId, int year, int month) {
        ensureSeeded(userId);
        List<PlanCategory> categories = categoryRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(userId);
        BigDecimal monthlyLimit = settingsRepository.findByUserId(userId)
            .map(PlannerSettings::getMonthlyLimit).orElse(PlanCategoryDefaults.DEFAULT_MONTHLY_LIMIT);

        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        String yearMonth = YearMonth.of(year, month).toString();

        List<Expense> monthExpenses = expenseRepository
            .findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, from, to);

        // Sinking-fund-linked categories (travel funds) pull "actual" from the fund's ledger for
        // this month, not from classified Expense rows — see PlanCategory.linkedSinkingFundName.
        Map<String, SinkingFund> fundsByName = sinkingFundRepository.findByUserIdAndActiveTrueOrderBySortOrderAsc(userId)
            .stream().collect(Collectors.toMap(SinkingFund::getName, f -> f, (a, b) -> a));

        // Bucket every non-fund-linked expense under its effective category key.
        Map<String, List<Expense>> byKey = new HashMap<>();
        for (Expense e : monthExpenses) {
            String key = effectiveKey(e, categories);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        List<CategoryPlanLine> lines = new ArrayList<>();
        Map<String, BigDecimal> groupPlanned = new LinkedHashMap<>();
        Map<String, BigDecimal> groupActual = new LinkedHashMap<>();
        BigDecimal plannedTotal = BigDecimal.ZERO;
        BigDecimal actualTotal = BigDecimal.ZERO;
        String biggestExpenseCategory = null;
        BigDecimal biggestExpenseAmount = BigDecimal.ZERO;

        for (PlanCategory cat : categories) {
            BigDecimal planned = nz(cat.getPlannedAmount());
            BigDecimal actual;
            List<PlanTransaction> txns;

            if (cat.getLinkedSinkingFundName() != null) {
                SinkingFund fund = fundsByName.get(cat.getLinkedSinkingFundName());
                actual = fund == null ? BigDecimal.ZERO
                    : sinkingFundEntryRepository.findByFundIdAndYearMonth(fund.getId(), yearMonth)
                        .map(SinkingFundEntry::getAdded).orElse(BigDecimal.ZERO);
                txns = List.of();
            } else {
                List<Expense> matched = byKey.getOrDefault(cat.getKey(), List.of());
                actual = matched.stream().map(Expense::getAmount).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                txns = matched.stream().map(e -> toTransactionDto(e, categories)).collect(Collectors.toList());
            }

            plannedTotal = plannedTotal.add(planned);
            actualTotal = actualTotal.add(actual);
            groupPlanned.merge(nvlGroup(cat.getGroupName()), planned, BigDecimal::add);
            groupActual.merge(nvlGroup(cat.getGroupName()), actual, BigDecimal::add);

            if (actual.compareTo(biggestExpenseAmount) > 0) {
                biggestExpenseAmount = actual;
                biggestExpenseCategory = cat.getName();
            }

            lines.add(CategoryPlanLine.builder()
                .key(cat.getKey()).name(cat.getName()).groupName(cat.getGroupName())
                .planned(planned).actual(actual).remaining(planned.subtract(actual))
                .linkedToSinkingFund(cat.getLinkedSinkingFundName() != null)
                .transactions(txns)
                .build());
        }

        List<GroupSummary> groupSummaries = groupPlanned.keySet().stream()
            .map(g -> GroupSummary.builder()
                .groupName(g).planned(groupPlanned.get(g)).actual(groupActual.getOrDefault(g, BigDecimal.ZERO))
                .difference(groupPlanned.get(g).subtract(groupActual.getOrDefault(g, BigDecimal.ZERO)))
                .build())
            .collect(Collectors.toList());

        return MonthlyPlanResponse.builder()
            .year(year).month(month)
            .monthlyLimit(monthlyLimit)
            .plannedTotal(plannedTotal)
            .actualTotal(actualTotal)
            .remaining(monthlyLimit.subtract(actualTotal))
            .buffer(monthlyLimit.subtract(plannedTotal))
            .status(status(actualTotal, plannedTotal, monthlyLimit))
            .categories(lines)
            .groupSummaries(groupSummaries)
            .biggestExpenseCategory(biggestExpenseCategory)
            .build();
    }

    /** Deliberately simple, named thresholds rather than a black-box score — matches the PDF's
     *  own four-state status line, tunable in one place. */
    private static String status(BigDecimal actual, BigDecimal planned, BigDecimal limit) {
        if (actual.compareTo(limit) > 0) return "OVER_LIMIT";
        if (actual.compareTo(limit.multiply(new BigDecimal("0.95"))) >= 0) return "LIMIT_REACHED";
        if (actual.compareTo(planned) >= 0) return "NEAR_LIMIT";
        return "WITHIN_PLAN";
    }

    private String effectiveKey(Expense e, List<PlanCategory> categories) {
        if (e.getPlanCategoryOverride() != null && !e.getPlanCategoryOverride().isBlank()) {
            return e.getPlanCategoryOverride();
        }
        String classified = classifier.classify(categories, e.getMerchant(), e.getDescription());
        return classified != null ? classified : "MISCELLANEOUS";
    }

    private PlanTransaction toTransactionDto(Expense e, List<PlanCategory> categories) {
        String ai = classifier.classify(categories, e.getMerchant(), e.getDescription());
        boolean overridden = e.getPlanCategoryOverride() != null && !e.getPlanCategoryOverride().isBlank();
        return PlanTransaction.builder()
            .expenseId(e.getId()).date(e.getExpenseDate()).merchant(e.getMerchant())
            .description(e.getDescription()).amount(e.getAmount()).paymentMethod(e.getPaymentMethod())
            .sourceEmailId(e.getSourceEmailId())
            .aiCategoryKey(ai)
            .finalCategoryKey(overridden ? e.getPlanCategoryOverride() : ai)
            .overridden(overridden)
            .build();
    }

    // ---- Month-end reflection -------------------------------------------------------------

    public ReflectionResponse getReflection(Long userId, int year, int month) {
        String ym = YearMonth.of(year, month).toString();
        MonthlyReflection r = reflectionRepository.findByUserIdAndYearMonth(userId, ym).orElse(null);
        MonthlyPlanResponse plan = getMonthlyPlan(userId, year, month);
        if (r == null) {
            return ReflectionResponse.builder().yearMonth(ym).suggestedStatus(suggestFinalStatus(plan)).build();
        }
        return ReflectionResponse.builder()
            .yearMonth(ym).biggestExpenseNote(r.getBiggestExpenseNote()).overspendNote(r.getOverspendNote())
            .underspendNote(r.getUnderspendNote()).oneOffNote(r.getOneOffNote())
            .adjustmentsNote(r.getAdjustmentsNote()).finalStatus(r.getFinalStatus())
            .suggestedStatus(suggestFinalStatus(plan))
            .build();
    }

    @Transactional
    public ReflectionResponse updateReflection(Long userId, int year, int month, ReflectionRequest req) {
        String ym = YearMonth.of(year, month).toString();
        MonthlyReflection r = reflectionRepository.findByUserIdAndYearMonth(userId, ym)
            .orElseGet(() -> MonthlyReflection.builder().userId(userId).yearMonth(ym).build());
        r.setBiggestExpenseNote(req.getBiggestExpenseNote());
        r.setOverspendNote(req.getOverspendNote());
        r.setUnderspendNote(req.getUnderspendNote());
        r.setOneOffNote(req.getOneOffNote());
        r.setAdjustmentsNote(req.getAdjustmentsNote());
        r.setFinalStatus(req.getFinalStatus());
        r.setUpdatedAt(java.time.LocalDateTime.now());
        reflectionRepository.save(r);
        return getReflection(userId, year, month);
    }

    /** UNDER / EXACT / OVER — matches the PDF's three checkboxes; a default the UI can preselect,
     *  never written unless the user explicitly saves a reflection. */
    private static String suggestFinalStatus(MonthlyPlanResponse plan) {
        int cmp = plan.getActualTotal().compareTo(plan.getMonthlyLimit());
        return cmp == 0 ? "EXACT" : (cmp < 0 ? "UNDER" : "OVER");
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String nvlGroup(String g) { return g == null || g.isBlank() ? "Ungrouped" : g; }

    private CategoryResponse toDto(PlanCategory c) {
        return CategoryResponse.builder()
            .id(c.getId()).key(c.getKey()).name(c.getName()).groupName(c.getGroupName())
            .plannedAmount(c.getPlannedAmount()).sortOrder(c.getSortOrder()).keywords(c.getKeywords())
            .fallback(c.isFallback()).linkedSinkingFundName(c.getLinkedSinkingFundName()).active(c.isActive())
            .build();
    }

    private static String deriveKey(String name) {
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category name is required");
        return name.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    }
}
