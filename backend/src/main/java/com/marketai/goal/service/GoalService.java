package com.marketai.goal.service;

import com.marketai.goal.dto.GoalDtos.*;
import com.marketai.goal.entity.FinancialGoal;
import com.marketai.goal.repository.FinancialGoalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GoalService {

    private final FinancialGoalRepository repo;

    public GoalResponse add(Long userId, GoalRequest r) {
        FinancialGoal g = FinancialGoal.builder()
            .userId(userId)
            .name(r.getName())
            .category(r.getCategory() != null ? r.getCategory() : "Other")
            .targetAmount(r.getTargetAmount())
            .currentSaved(r.getCurrentSaved() != null ? r.getCurrentSaved() : BigDecimal.ZERO)
            .monthlyContribution(r.getMonthlyContribution() != null ? r.getMonthlyContribution() : BigDecimal.ZERO)
            .expectedReturn(r.getExpectedReturn() != null ? r.getExpectedReturn() : new BigDecimal("10"))
            .targetDate(r.getTargetDate())
            .build();
        return toDto(repo.save(g));
    }

    public GoalResponse update(Long userId, Long id, GoalRequest r) {
        FinancialGoal g = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (r.getName() != null) g.setName(r.getName());
        if (r.getCategory() != null) g.setCategory(r.getCategory());
        if (r.getTargetAmount() != null) g.setTargetAmount(r.getTargetAmount());
        if (r.getCurrentSaved() != null) g.setCurrentSaved(r.getCurrentSaved());
        if (r.getMonthlyContribution() != null) g.setMonthlyContribution(r.getMonthlyContribution());
        if (r.getExpectedReturn() != null) g.setExpectedReturn(r.getExpectedReturn());
        if (r.getTargetDate() != null) g.setTargetDate(r.getTargetDate());
        return toDto(repo.save(g));
    }

    public List<GoalResponse> list(Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toDto).collect(Collectors.toList());
    }

    public void delete(Long userId, Long id) {
        repo.findByIdAndUserId(id, userId).ifPresent(repo::delete);
    }

    /**
     * PocketSmith-style "what if" simulation: re-runs the projection with a hypothetical SIP
     * pause or lump sum, purely in memory. Never touches {@link #repo} for anything but the
     * read — the real goal and its SIP are left exactly as they are.
     */
    public WhatIfResponse whatIf(Long userId, Long id, WhatIfRequest req) {
        FinancialGoal g = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        SimResult baselineSim = simulate(g, null);
        SimResult scenarioSim = simulate(g, req);

        WhatIfProjection baseline = toProjection(baselineSim, g);
        WhatIfProjection scenario = toProjection(scenarioSim, g);

        Integer delay = (baselineSim.completionMonth() != null && scenarioSim.completionMonth() != null)
            ? scenarioSim.completionMonth() - baselineSim.completionMonth()
            : null;

        return WhatIfResponse.builder()
            .goalId(g.getId())
            .baseline(baseline)
            .scenario(scenario)
            .completionDelayMonths(delay)
            .projectedValueDelta(scenario.getProjectedValue().subtract(baseline.getProjectedValue()))
            .build();
    }

    // 50 years — bounds the completion-date search so a goal that never completes doesn't loop forever.
    private static final int MAX_SIMULATION_MONTHS = 600;

    private record SimResult(double valueAtHorizon, Integer completionMonth) {}

    /**
     * Month-by-month simulation (rather than the closed-form annuity {@link #toDto} uses)
     * because a pause window or a one-off lump sum breaks the "constant contribution every
     * month" assumption the closed form relies on. {@code adj == null} runs the baseline,
     * unmodified schedule.
     */
    private SimResult simulate(FinancialGoal g, WhatIfRequest adj) {
        double target = g.getTargetAmount().doubleValue();
        double balance = g.getCurrentSaved() != null ? g.getCurrentSaved().doubleValue() : 0;
        double sip = g.getMonthlyContribution() != null ? g.getMonthlyContribution().doubleValue() : 0;
        double annual = g.getExpectedReturn() != null ? g.getExpectedReturn().doubleValue() : 10;
        double r = annual / 100.0 / 12.0;

        Integer horizonMonths = g.getTargetDate() != null
            ? (int) Math.max(0, ChronoUnit.MONTHS.between(LocalDate.now(), g.getTargetDate()))
            : null;
        // No target date: mirror toDto's "1yr illustration" window for the point-in-time value.
        int valueWindow = horizonMonths != null ? horizonMonths : 12;

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

        Integer completionMonth = balance >= target ? 0 : null;
        Double valueAtHorizon = valueWindow == 0 ? balance : null;

        for (int m = 1; m <= MAX_SIMULATION_MONTHS; m++) {
            double contribution = (m >= pauseStart && m <= pauseEnd) ? 0 : sip;
            balance = balance * (1 + r) + contribution;
            if (m == lumpSumMonth) balance += lumpSumAmount;

            if (completionMonth == null && balance >= target) completionMonth = m;
            if (m == valueWindow) valueAtHorizon = balance;
            if (completionMonth != null && m >= valueWindow) break;
        }
        if (valueAtHorizon == null) valueAtHorizon = balance; // horizon beyond the search cap — best available estimate

        return new SimResult(valueAtHorizon, completionMonth);
    }

    private WhatIfProjection toProjection(SimResult sim, FinancialGoal g) {
        double target = g.getTargetAmount().doubleValue();
        boolean onTrack = sim.valueAtHorizon() >= target;
        LocalDate completionDate = sim.completionMonth() != null ? LocalDate.now().plusMonths(sim.completionMonth()) : null;

        return WhatIfProjection.builder()
            .projectedValue(BigDecimal.valueOf(sim.valueAtHorizon()).setScale(0, RoundingMode.HALF_UP))
            .completionMonth(sim.completionMonth())
            .completionDate(completionDate)
            .onTrack(onTrack)
            .shortfall(BigDecimal.valueOf(Math.max(0, target - sim.valueAtHorizon())).setScale(0, RoundingMode.HALF_UP))
            .build();
    }

    private GoalResponse toDto(FinancialGoal g) {
        double target = g.getTargetAmount().doubleValue();
        double saved = g.getCurrentSaved() != null ? g.getCurrentSaved().doubleValue() : 0;
        double sip = g.getMonthlyContribution() != null ? g.getMonthlyContribution().doubleValue() : 0;
        double annual = g.getExpectedReturn() != null ? g.getExpectedReturn().doubleValue() : 10;
        double r = annual / 100.0 / 12.0; // monthly rate

        Integer months = null;
        if (g.getTargetDate() != null) {
            months = (int) Math.max(0, ChronoUnit.MONTHS.between(LocalDate.now(), g.getTargetDate()));
        }

        double progress = target > 0 ? Math.min(100, saved / target * 100) : 0;

        // Future value of current corpus + SIP annuity over the horizon
        double projected = target;
        BigDecimal requiredMonthly = null;
        String status;
        boolean onTrack;

        if (saved >= target) {
            status = "ACHIEVED"; onTrack = true; projected = saved;
        } else if (months != null && months == 0) {
            // The target date is today or already past, so there is no horizon left to spread a
            // contribution over. Every "required monthly" formula divides by the horizon here
            // (r > 0 divides by pow(1+r,0)-1 == 0), which produced Infinity and then threw out of
            // BigDecimal.valueOf — taking the whole goals list down with a 500. There is no monthly
            // figure to report: the shortfall is simply the amount still missing, today.
            projected = saved;
            onTrack = false;
            status = "SHORTFALL";
        } else if (months != null) {
            double fvLump = saved * Math.pow(1 + r, months);
            double fvSip = r > 0 ? sip * ((Math.pow(1 + r, months) - 1) / r) : sip * months;
            projected = fvLump + fvSip;
            onTrack = projected >= target;
            status = onTrack ? "ON_TRACK" : "SHORTFALL";
            // SIP required to hit target exactly by the date
            double remaining = target - fvLump;
            double reqSip = remaining <= 0 ? 0 : (r > 0 ? remaining * r / (Math.pow(1 + r, months) - 1) : remaining / months);
            requiredMonthly = BigDecimal.valueOf(Math.max(0, reqSip)).setScale(0, RoundingMode.HALF_UP);
        } else {
            // no target date — just project on current corpus + 1yr of SIP as illustration
            projected = saved + sip * 12;
            onTrack = projected >= target;
            status = onTrack ? "ON_TRACK" : "SHORTFALL";
        }

        return GoalResponse.builder()
            .id(g.getId()).name(g.getName()).category(g.getCategory())
            .targetAmount(g.getTargetAmount()).currentSaved(g.getCurrentSaved())
            .monthlyContribution(g.getMonthlyContribution()).expectedReturn(g.getExpectedReturn())
            .targetDate(g.getTargetDate())
            .progressPercent(Math.round(progress * 10) / 10.0)
            .projectedValue(BigDecimal.valueOf(projected).setScale(0, RoundingMode.HALF_UP))
            .onTrack(onTrack).monthsToTarget(months).requiredMonthly(requiredMonthly)
            .status(status)
            .build();
    }
}
