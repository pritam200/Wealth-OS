package com.marketai.goal;

import com.marketai.goal.dto.GoalDtos.GoalResponse;
import com.marketai.goal.entity.FinancialGoal;
import com.marketai.goal.repository.FinancialGoalRepository;
import com.marketai.goal.service.GoalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A goal's projection must be computable for every date a user can actually pick — including
 * today, and including a date that has already passed.
 */
class GoalProjectionTest {

    private static final Long USER = 7L;

    private FinancialGoalRepository repo;
    private GoalService service;

    @BeforeEach
    void setUp() {
        repo = mock(FinancialGoalRepository.class);
        service = new GoalService(repo);
    }

    private void givenGoal(LocalDate targetDate, String target, String saved, String sip) {
        when(repo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(
            FinancialGoal.builder().id(1L).userId(USER).name("House").category("Home")
                .targetAmount(new BigDecimal(target)).currentSaved(new BigDecimal(saved))
                .monthlyContribution(new BigDecimal(sip)).expectedReturn(new BigDecimal("10"))
                .targetDate(targetDate).build()));
    }

    /**
     * Regression: a target date of today leaves a zero-month horizon, and every "required
     * monthly" formula divides by that horizon — {@code pow(1+r, 0) - 1} is exactly 0. The result
     * was Infinity, {@code BigDecimal.valueOf(Infinity)} threw NumberFormatException, and the
     * entire goals list returned 500 because of one goal reaching its date.
     */
    @Test
    void aGoalWhoseTargetDateIsTodayStillProjects() {
        givenGoal(LocalDate.now(), "5000000", "1200000", "25000");

        List<GoalResponse> goals = service.list(USER);

        assertThat(goals).hasSize(1);
        GoalResponse g = goals.get(0);
        assertThat(g.getMonthsToTarget()).isZero();
        assertThat(g.getStatus()).isEqualTo("SHORTFALL");
        // No horizon left, so there is no monthly figure to report — reporting one would mean
        // dividing by zero months.
        assertThat(g.getRequiredMonthly()).isNull();
        assertThat(g.getProjectedValue()).isEqualByComparingTo("1200000");
    }

    @Test
    void aGoalWhoseTargetDateHasPassedStillProjects() {
        givenGoal(LocalDate.now().minusMonths(3), "5000000", "1200000", "25000");

        GoalResponse g = service.list(USER).get(0);

        // MONTHS.between is clamped at 0 for a past date, so this lands in the same branch.
        assertThat(g.getMonthsToTarget()).isZero();
        assertThat(g.isOnTrack()).isFalse();
    }

    @Test
    void anAlreadyAchievedGoalIsReportedAchievedEvenOnItsTargetDate() {
        givenGoal(LocalDate.now(), "1000000", "1000000", "0");

        GoalResponse g = service.list(USER).get(0);

        assertThat(g.getStatus()).isEqualTo("ACHIEVED");
        assertThat(g.getProgressPercent()).isEqualTo(100.0);
    }

    @Test
    void aNormalHorizonStillReportsARequiredMonthlyContribution() {
        givenGoal(LocalDate.now().plusYears(5), "5000000", "1200000", "25000");

        GoalResponse g = service.list(USER).get(0);

        assertThat(g.getMonthsToTarget()).isEqualTo(60);
        assertThat(g.getRequiredMonthly()).isNotNull();
        assertThat(g.getRequiredMonthly()).isGreaterThan(BigDecimal.ZERO);
    }
}
