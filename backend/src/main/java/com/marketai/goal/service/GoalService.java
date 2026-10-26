package com.marketai.goal.service;

import com.marketai.goal.dto.GoalDtos.*;
import com.marketai.goal.entity.FinancialGoal;
import com.marketai.goal.repository.FinancialGoalRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.scheduled.entity.RecurringInvestment;
import com.marketai.scheduled.repository.RecurringInvestmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GoalService {

    /** Used when a new goal doesn't say: India's long-run consumer inflation, roughly. */
    static final BigDecimal DEFAULT_INFLATION = new BigDecimal("6");

    private final FinancialGoalRepository repo;
    private final HoldingRepository holdings;
    private final RecurringInvestmentRepository sips;

    @Transactional
    public GoalResponse add(Long userId, GoalRequest r) {
        if (r.getTargetDate() != null && r.getTargetDate().isBefore(LocalDate.now()))
            throw new IllegalArgumentException("The target date has already passed");
        FinancialGoal g = FinancialGoal.builder().userId(userId).build();
        apply(userId, g, r, DEFAULT_INFLATION);
        return toDto(repo.save(g), links(userId));
    }

    /** Replaces the whole goal with the request, as the edit form sends every field. */
    @Transactional
    public GoalResponse update(Long userId, Long id, GoalRequest r) {
        FinancialGoal g = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // A goal already past its date can be edited without moving the date; a new date must be ahead.
        if (r.getTargetDate() != null && !r.getTargetDate().equals(g.getTargetDate()) && r.getTargetDate().isBefore(LocalDate.now()))
            throw new IllegalArgumentException("The target date has already passed");
        apply(userId, g, r, BigDecimal.ZERO);
        return toDto(repo.save(g), links(userId));
    }

    private void apply(Long userId, FinancialGoal g, GoalRequest r, BigDecimal inflationIfBlank) {
        g.setName(r.getName().trim());
        g.setCategory(r.getCategory() != null && !r.getCategory().isBlank() ? r.getCategory() : "Other");
        g.setTargetAmount(r.getTargetAmount());
        g.setCurrentSaved(r.getCurrentSaved() != null ? r.getCurrentSaved() : BigDecimal.ZERO);
        g.setMonthlyContribution(r.getMonthlyContribution() != null ? r.getMonthlyContribution() : BigDecimal.ZERO);
        g.setExpectedReturn(r.getExpectedReturn() != null ? r.getExpectedReturn() : new BigDecimal("10"));
        g.setInflationRate(r.getInflationRate() != null ? r.getInflationRate() : inflationIfBlank);
        g.setTargetDate(r.getTargetDate());

        Links own = links(userId);
        Set<Long> h = r.getLinkedHoldingIds() != null ? new HashSet<>(r.getLinkedHoldingIds()) : new HashSet<>();
        Set<Long> s = r.getLinkedSipIds() != null ? new HashSet<>(r.getLinkedSipIds()) : new HashSet<>();
        if (!own.holdings().keySet().containsAll(h)) throw new IllegalArgumentException("A linked holding isn't one of yours, or is sold");
        if (!own.sips().keySet().containsAll(s)) throw new IllegalArgumentException("A linked SIP isn't one of yours");
        g.getLinkedHoldingIds().clear();
        g.getLinkedHoldingIds().addAll(h);
        g.getLinkedSipIds().clear();
        g.getLinkedSipIds().addAll(s);
    }

    @Transactional(readOnly = true)
    public List<GoalResponse> list(Long userId) {
        Links links = links(userId);
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(g -> toDto(g, links)).collect(Collectors.toList());
    }

    @Transactional
    public void delete(Long userId, Long id) {
        repo.delete(repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    /** The holdings and SIPs the user can count toward a goal. */
    @Transactional(readOnly = true)
    public LinkOptions linkOptions(Long userId) {
        Links l = links(userId);
        return LinkOptions.builder()
            .holdings(l.holdings().values().stream().map(h -> LinkOption.builder()
                .id(h.getId()).label(h.getName() != null ? h.getName() : h.getSymbol())
                .detail(h.getPortfolio() != null ? h.getPortfolio().getName() : null)
                .amount(value(h).setScale(0, RoundingMode.HALF_UP)).build()).toList())
            .sips(l.sips().values().stream().map(s -> LinkOption.builder()
                .id(s.getId()).label(s.getLabel()).detail(s.getStatus()).amount(s.getAmount()).build()).toList())
            .build();
    }

    /**
     * PocketSmith-style "what if" simulation: re-runs the projection with a hypothetical SIP
     * pause or lump sum, purely in memory. Never touches {@link #repo} for anything but the
     * read — the real goal and its SIP are left exactly as they are.
     */
    @Transactional(readOnly = true)
    public WhatIfResponse whatIf(Long userId, Long id, WhatIfRequest req) {
        FinancialGoal g = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Inputs in = inputs(g, links(userId));

        SimResult baselineSim = simulate(in, null);
        SimResult scenarioSim = simulate(in, req);

        WhatIfProjection baseline = toProjection(baselineSim, in);
        WhatIfProjection scenario = toProjection(scenarioSim, in);

        Integer delay = (baselineSim.completionMonth() != null && scenarioSim.completionMonth() != null)
            ? scenarioSim.completionMonth() - baselineSim.completionMonth()
            : null;

        return WhatIfResponse.builder()
            .goalId(g.getId())
            .baseline(baseline)
            .scenario(scenario)
            .scenarioNeverCompletes(baselineSim.completionMonth() != null && scenarioSim.completionMonth() == null)
            .completionDelayMonths(delay)
            .projectedValueDelta(scenario.getProjectedValue().subtract(baseline.getProjectedValue()))
            .build();
    }

    private record Links(Map<Long, Holding> holdings, Map<Long, RecurringInvestment> sips) {}

    private Links links(Long userId) {
        Map<Long, Holding> h = holdings.findOpenByUserIdWithPortfolio(userId).stream()
            .collect(Collectors.toMap(Holding::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Long, RecurringInvestment> s = sips.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .collect(Collectors.toMap(RecurringInvestment::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        return new Links(h, s);
    }

    /** Market value, or cost when there's no price yet. */
    private static BigDecimal value(Holding h) {
        BigDecimal qty = h.getQuantity() != null ? h.getQuantity() : BigDecimal.ZERO;
        BigDecimal px = h.getCurrentPrice() != null && h.getCurrentPrice().signum() > 0 ? h.getCurrentPrice()
            : h.getAverageCost() != null ? h.getAverageCost() : BigDecimal.ZERO;
        return qty.multiply(px);
    }

    /**
     * Everything a projection needs, with linked holdings added to what's saved and active
     * linked SIPs to the monthly amount. A linked holding that has since been sold, or a SIP
     * since deleted, simply counts nothing.
     */
    private record Inputs(double target, double saved, double sip, double monthlyRate, double inflation,
                          LocalDate targetDate, Integer months, double linkedValue, double linkedMonthly) {
        /** The target in rupees of {@code month} months from now. */
        double targetAt(int month) {
            return target * Math.pow(1 + inflation / 100.0, month / 12.0);
        }
    }

    private Inputs inputs(FinancialGoal g, Links links) {
        double linkedValue = g.getLinkedHoldingIds().stream().map(links.holdings()::get).filter(Objects::nonNull)
            .mapToDouble(h -> value(h).doubleValue()).sum();
        double linkedMonthly = g.getLinkedSipIds().stream().map(links.sips()::get).filter(Objects::nonNull)
            .filter(s -> "ACTIVE".equals(s.getStatus()) && s.getAmount() != null)
            .mapToDouble(s -> s.getAmount().doubleValue()).sum();
        double saved = (g.getCurrentSaved() != null ? g.getCurrentSaved().doubleValue() : 0) + linkedValue;
        double sip = (g.getMonthlyContribution() != null ? g.getMonthlyContribution().doubleValue() : 0) + linkedMonthly;
        double annual = g.getExpectedReturn() != null ? g.getExpectedReturn().doubleValue() : 10;
        double inflation = g.getInflationRate() != null ? g.getInflationRate().doubleValue() : 0;
        return new Inputs(g.getTargetAmount().doubleValue(), saved, sip, annual / 100.0 / 12.0, inflation,
            g.getTargetDate(), monthsTo(g.getTargetDate()), linkedValue, linkedMonthly);
    }

    /**
     * Whole months to the target date, counting a part month as one: a date two weeks away
     * still leaves a contribution to make. Zero for today or a date already past.
     */
    static Integer monthsTo(LocalDate targetDate) {
        if (targetDate == null) return null;
        LocalDate today = LocalDate.now();
        if (!targetDate.isAfter(today)) return 0;
        long months = ChronoUnit.MONTHS.between(today, targetDate);
        if (today.plusMonths(months).isBefore(targetDate)) months++;
        return (int) months;
    }

    // 50 years — bounds the completion-date search so a goal that never completes doesn't loop forever.
    private static final int MAX_SIMULATION_MONTHS = 600;
    /** Without a target date, a goal is projected a year ahead as an illustration. */
    private static final int NO_DATE_WINDOW = 12;

    private record SimResult(double valueAtHorizon, Integer completionMonth) {}

    /**
     * Month-by-month simulation, the one projection both the goal card and the what-if use. A
     * pause window or a one-off lump sum breaks the "constant contribution every month"
     * assumption a closed form relies on. {@code adj == null} runs the baseline, unmodified
     * schedule. The target grows with inflation month by month, so "complete" means reaching
     * the target as it will be then.
     */
    private SimResult simulate(Inputs in, WhatIfRequest adj) {
        double balance = in.saved();
        int valueWindow = in.months() != null ? in.months() : NO_DATE_WINDOW;

        int pauseStart = -1, pauseEnd = -1;
        int lumpSumMonth = -1;
        double lumpSumAmount = 0;
        if (adj != null && adj.getAdjustmentType() != null) {
            int startMonth = adj.getStartMonth() != null ? Math.max(1, adj.getStartMonth()) : 1;
            if ("PAUSE_SIP".equalsIgnoreCase(adj.getAdjustmentType())) {
                pauseStart = startMonth;
                pauseEnd = pauseStart + (adj.getPauseMonths() != null ? adj.getPauseMonths() : 0) - 1;
            } else if ("LUMP_SUM".equalsIgnoreCase(adj.getAdjustmentType())) {
                lumpSumMonth = startMonth;
                lumpSumAmount = adj.getLumpSumAmount() != null ? adj.getLumpSumAmount().doubleValue() : 0;
            }
        }

        Integer completionMonth = balance >= in.targetAt(0) ? 0 : null;
        Double valueAtHorizon = valueWindow == 0 ? balance : null;

        for (int m = 1; m <= MAX_SIMULATION_MONTHS; m++) {
            double contribution = (m >= pauseStart && m <= pauseEnd) ? 0 : in.sip();
            balance = balance * (1 + in.monthlyRate()) + contribution;
            if (m == lumpSumMonth) balance += lumpSumAmount;

            if (completionMonth == null && balance >= in.targetAt(m)) completionMonth = m;
            if (m == valueWindow) valueAtHorizon = balance;
            if (completionMonth != null && m >= valueWindow) break;
        }
        if (valueAtHorizon == null) valueAtHorizon = balance; // horizon beyond the search cap — best available estimate

        return new SimResult(valueAtHorizon, completionMonth);
    }

    /** The target at the horizon the projection is valued at. */
    private static double horizonTarget(Inputs in) {
        return in.months() != null ? in.targetAt(in.months()) : in.target();
    }

    private WhatIfProjection toProjection(SimResult sim, Inputs in) {
        double target = horizonTarget(in);
        boolean onTrack = sim.valueAtHorizon() >= target;
        LocalDate completionDate = sim.completionMonth() != null ? LocalDate.now().plusMonths(sim.completionMonth()) : null;

        return WhatIfProjection.builder()
            .projectedValue(round(sim.valueAtHorizon()))
            .completionMonth(sim.completionMonth())
            .completionDate(completionDate)
            .onTrack(onTrack)
            .shortfall(round(Math.max(0, target - sim.valueAtHorizon())))
            .build();
    }

    private GoalResponse toDto(FinancialGoal g, Links links) {
        Inputs in = inputs(g, links);
        double saved = in.saved();
        double target = horizonTarget(in);
        Integer months = in.months();
        double progress = target > 0 ? Math.min(100, saved / target * 100) : 0;

        double projected;
        BigDecimal requiredMonthly = null;
        String status;
        boolean onTrack;

        if (saved >= target) {
            status = "ACHIEVED"; onTrack = true; projected = saved;
        } else if (months != null && months == 0) {
            // The target date is today or already past, so there is no horizon left to spread a
            // contribution over, and no monthly figure to report: the shortfall is simply the
            // amount still missing, today. Past the date, the goal is overdue rather than short.
            projected = saved;
            onTrack = false;
            status = in.targetDate().isBefore(LocalDate.now()) ? "OVERDUE" : "SHORTFALL";
        } else {
            projected = simulate(in, null).valueAtHorizon();
            onTrack = projected >= target;
            status = onTrack ? "ON_TRACK" : "SHORTFALL";
            if (months != null) {
                // SIP required to hit the target exactly by the date, from the same month-end
                // contribution the simulation uses.
                double r = in.monthlyRate();
                double fvLump = saved * Math.pow(1 + r, months);
                double remaining = target - fvLump;
                double reqSip = remaining <= 0 ? 0 : (r > 0 ? remaining * r / (Math.pow(1 + r, months) - 1) : remaining / months);
                requiredMonthly = round(Math.max(0, reqSip));
            }
        }

        return GoalResponse.builder()
            .id(g.getId()).name(g.getName()).category(g.getCategory())
            .targetAmount(g.getTargetAmount()).currentSaved(g.getCurrentSaved())
            .monthlyContribution(g.getMonthlyContribution()).expectedReturn(g.getExpectedReturn())
            .targetDate(g.getTargetDate())
            .inflationRate(g.getInflationRate())
            .linkedHoldingIds(new TreeSet<>(g.getLinkedHoldingIds()))
            .linkedSipIds(new TreeSet<>(g.getLinkedSipIds()))
            .linkedValue(round(in.linkedValue()))
            .linkedMonthly(round(in.linkedMonthly()))
            .totalSaved(round(saved))
            .totalMonthly(round(in.sip()))
            .inflatedTarget(round(target))
            .shortfallAmount(onTrack ? BigDecimal.ZERO : round(Math.max(0, target - projected)))
            .progressPercent(Math.round(progress * 10) / 10.0)
            .projectedValue(round(projected))
            .onTrack(onTrack).monthsToTarget(months).requiredMonthly(requiredMonthly)
            .status(status)
            .build();
    }

    private static BigDecimal round(double v) {
        return BigDecimal.valueOf(v).setScale(0, RoundingMode.HALF_UP);
    }
}
