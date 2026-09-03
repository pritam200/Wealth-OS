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
