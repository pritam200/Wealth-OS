package com.marketai.goal;

import com.marketai.goal.dto.GoalDtos.GoalRequest;
import com.marketai.goal.dto.GoalDtos.GoalResponse;
import com.marketai.goal.entity.FinancialGoal;
import com.marketai.goal.repository.FinancialGoalRepository;
import com.marketai.goal.service.GoalService;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.scheduled.entity.RecurringInvestment;
import com.marketai.scheduled.repository.RecurringInvestmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Editing a goal, and counting holdings, SIPs and inflation toward it. */
class GoalServiceTest {

    private static final Long USER = 7L;

    private FinancialGoalRepository repo;
    private HoldingRepository holdings;
    private RecurringInvestmentRepository sips;
    private GoalService service;

    @BeforeEach
    void setUp() {
        repo = mock(FinancialGoalRepository.class);
        holdings = mock(HoldingRepository.class);
        sips = mock(RecurringInvestmentRepository.class);
        service = new GoalService(repo, holdings, sips);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private GoalRequest request(String target, LocalDate date) {
        GoalRequest r = new GoalRequest();
        r.setName("House");
        r.setTargetAmount(new BigDecimal(target));
        r.setTargetDate(date);
        return r;
    }

    private FinancialGoal existing(LocalDate date) {
        return FinancialGoal.builder().id(1L).userId(USER).name("House").category("Home")
            .targetAmount(new BigDecimal("1000000")).currentSaved(new BigDecimal("100000"))
            .monthlyContribution(new BigDecimal("5000")).expectedReturn(new BigDecimal("10"))
            .inflationRate(new BigDecimal("6")).targetDate(date).build();
    }

    @Test
    void anUpdateReplacesEveryFieldSoATargetDateCanBeCleared() {
        FinancialGoal g = existing(LocalDate.now().plusYears(3));
        when(repo.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(g));

        GoalResponse res = service.update(USER, 1L, request("2000000", null));

        assertThat(res.getTargetDate()).isNull();
        assertThat(res.getTargetAmount()).isEqualByComparingTo("2000000");
        assertThat(res.getCurrentSaved()).isEqualByComparingTo("0");
        assertThat(g.getCategory()).isEqualTo("Other");
    }

    @Test
    void aGoalPastItsDateCanBeEditedWithoutMovingTheDate() {
        LocalDate past = LocalDate.now().minusMonths(2);
        when(repo.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(existing(past)));

        assertThat(service.update(USER, 1L, request("900000", past)).getStatus()).isEqualTo("OVERDUE");
        assertThatThrownBy(() -> service.update(USER, 1L, request("900000", past.minusDays(1))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(USER, request("900000", past)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingAGoalThatIsntYoursIsNotFound() {
        when(repo.findByIdAndUserId(9L, USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(USER, 9L)).isInstanceOf(ResponseStatusException.class);
        verify(repo, never()).delete(any());
    }

    @Test
    void inflationGrowsTheTargetByTheTargetDate() {
        GoalRequest r = request("1000000", LocalDate.now().plusYears(10));
        r.setInflationRate(new BigDecimal("6"));

        GoalResponse res = service.add(USER, r);

        // 1.06^10 ≈ 1.79
        assertThat(res.getInflatedTarget().doubleValue()).isBetween(1_780_000.0, 1_800_000.0);
        assertThat(res.getTargetAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    void aNewGoalAssumesInflationUnlessToldNot() {
        assertThat(service.add(USER, request("1000000", null)).getInflationRate()).isEqualByComparingTo("6");
    }

    @Test
    void linkedHoldingsAndActiveSipsCountTowardTheGoal() {
        Holding h = Holding.builder().id(11L).name("Nifty ETF").quantity(new BigDecimal("100"))
            .currentPrice(new BigDecimal("250")).averageCost(new BigDecimal("200")).build();
        RecurringInvestment active = RecurringInvestment.builder().id(21L).label("Index SIP")
            .amount(new BigDecimal("3000")).status("ACTIVE").build();
        RecurringInvestment paused = RecurringInvestment.builder().id(22L).label("Old SIP")
            .amount(new BigDecimal("9000")).status("PAUSED").build();
        when(holdings.findOpenByUserIdWithPortfolio(USER)).thenReturn(List.of(h));
        when(sips.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(active, paused));

        GoalRequest r = request("1000000", LocalDate.now().plusYears(5));
        r.setCurrentSaved(new BigDecimal("5000"));
        r.setMonthlyContribution(new BigDecimal("1000"));
        r.setLinkedHoldingIds(Set.of(11L));
        r.setLinkedSipIds(Set.of(21L, 22L));

        GoalResponse res = service.add(USER, r);

        assertThat(res.getLinkedValue()).isEqualByComparingTo("25000");
        assertThat(res.getTotalSaved()).isEqualByComparingTo("30000");
        assertThat(res.getLinkedMonthly()).isEqualByComparingTo("3000");
        assertThat(res.getTotalMonthly()).isEqualByComparingTo("4000");
    }

    @Test
    void someoneElsesHoldingCannotBeLinked() {
        when(holdings.findOpenByUserIdWithPortfolio(USER)).thenReturn(List.of());
        GoalRequest r = request("1000000", null);
        r.setLinkedHoldingIds(Set.of(99L));
        assertThatThrownBy(() -> service.add(USER, r)).isInstanceOf(IllegalArgumentException.class);
    }
}
