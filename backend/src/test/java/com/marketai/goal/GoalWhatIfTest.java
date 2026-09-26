package com.marketai.goal;

import com.marketai.goal.dto.GoalDtos.WhatIfRequest;
import com.marketai.goal.dto.GoalDtos.WhatIfResponse;
import com.marketai.goal.entity.FinancialGoal;
import com.marketai.goal.repository.FinancialGoalRepository;
import com.marketai.goal.service.GoalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PocketSmith-style "what if" scenarios: pausing a SIP or dropping in a lump sum must change
 * the projection in the expected direction relative to the baseline, and must never write
 * anything back through the repository.
 */
class GoalWhatIfTest {

    private static final Long USER = 7L;
    private static final Long GOAL_ID = 1L;

    private FinancialGoalRepository repo;
    private GoalService service;

    @BeforeEach
    void setUp() {
        repo = mock(FinancialGoalRepository.class);
        service = new GoalService(repo);
    }

    private void givenGoal(LocalDate targetDate, String target, String saved, String sip) {
        when(repo.findByIdAndUserId(GOAL_ID, USER)).thenReturn(Optional.of(
            FinancialGoal.builder().id(GOAL_ID).userId(USER).name("House").category("Home")
                .targetAmount(new BigDecimal(target)).currentSaved(new BigDecimal(saved))
                .monthlyContribution(new BigDecimal(sip)).expectedReturn(new BigDecimal("10"))
                .targetDate(targetDate).build()));
    }

    @Test
    void pausingTheSipForSeveralMonthsWorsensTheProjectionVersusBaseline() {
        givenGoal(LocalDate.now().plusYears(5), "5000000", "1200000", "25000");

        WhatIfRequest pause = new WhatIfRequest();
        pause.setAdjustmentType("PAUSE_SIP");
        pause.setStartMonth(1);
        pause.setPauseMonths(3);

        WhatIfResponse res = service.whatIf(USER, GOAL_ID, pause);

        assertThat(res.getScenario().getProjectedValue())
            .isLessThan(res.getBaseline().getProjectedValue());
        assertThat(res.getProjectedValueDelta()).isNegative();
        // never mutates the real goal
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).save(org.mockito.Mockito.any());
    }

    @Test
    void pausingTheSipCanPushCompletionLaterWhenTheGoalHasNoFixedDate() {
        givenGoal(null, "500000", "0", "10000");

        WhatIfRequest pause = new WhatIfRequest();
        pause.setAdjustmentType("PAUSE_SIP");
        pause.setStartMonth(1);
        pause.setPauseMonths(6);

        WhatIfResponse res = service.whatIf(USER, GOAL_ID, pause);

        assertThat(res.getBaseline().getCompletionMonth()).isNotNull();
        assertThat(res.getScenario().getCompletionMonth()).isNotNull();
        assertThat(res.getScenario().getCompletionMonth())
            .isGreaterThan(res.getBaseline().getCompletionMonth());
        assertThat(res.getCompletionDelayMonths()).isGreaterThan(0);
    }

    @Test
    void aLumpSumImprovesTheProjectionAndCanPullCompletionEarlier() {
        givenGoal(null, "500000", "0", "10000");

        WhatIfRequest lumpSum = new WhatIfRequest();
        lumpSum.setAdjustmentType("LUMP_SUM");
        lumpSum.setStartMonth(1);
        lumpSum.setLumpSumAmount(new BigDecimal("200000"));

        WhatIfResponse res = service.whatIf(USER, GOAL_ID, lumpSum);

        assertThat(res.getScenario().getProjectedValue())
            .isGreaterThan(res.getBaseline().getProjectedValue());
        assertThat(res.getProjectedValueDelta()).isPositive();
        assertThat(res.getScenario().getCompletionMonth())
            .isLessThan(res.getBaseline().getCompletionMonth());
        assertThat(res.getCompletionDelayMonths()).isNegative();
    }

    @Test
    void aLumpSumLargeEnoughToAlreadyCoverTheGoalMarksItAchievedImmediately() {
        givenGoal(LocalDate.now().plusYears(2), "1000000", "100000", "5000");

        WhatIfRequest lumpSum = new WhatIfRequest();
        lumpSum.setAdjustmentType("LUMP_SUM");
        lumpSum.setStartMonth(1);
        lumpSum.setLumpSumAmount(new BigDecimal("2000000"));

        WhatIfResponse res = service.whatIf(USER, GOAL_ID, lumpSum);

        assertThat(res.getScenario().isOnTrack()).isTrue();
        assertThat(res.getScenario().getShortfall()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
