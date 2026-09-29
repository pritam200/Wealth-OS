package com.marketai.tracking.service;

import com.marketai.income.entity.Income;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Closing a deposit books its interest exactly once, on the day it was paid. */
class DepositCloseTest {

    private static final Long USER = 1L;

    private FixedDepositRepository fdRepo;
    private IncomeRepository incomeRepo;
    private TrackingService service;

    @BeforeEach
    void setUp() {
        fdRepo = mock(FixedDepositRepository.class);
        incomeRepo = mock(IncomeRepository.class);
        service = new TrackingService(fdRepo, mock(RecurringDepositRepository.class), mock(LoanRepository.class),
            mock(EpfAccountRepository.class), incomeRepo, mock(OtherAssetRepository.class));
        when(fdRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private FixedDeposit fd(String status, LocalDate maturity) {
        return FixedDeposit.builder().id(9L).bank("HDFC Bank")
            .principal(new BigDecimal("11877")).rate(new BigDecimal("6.5")).compounding("quarterly")
            .startDate(maturity.minusYears(1)).maturityDate(maturity).status(status).build();
    }

    @Test
    @DisplayName("a matured FD's interest is booked on its maturity date, not the day it was closed in the app")
    void interestIsDatedOnMaturity() {
        LocalDate maturity = LocalDate.now().minusDays(20);
        when(fdRepo.findByIdAndUserId(9L, USER)).thenReturn(Optional.of(fd("MATURED", maturity)));

        service.closeFd(9L, USER, new BigDecimal("12639"));

        ArgumentCaptor<Income> interest = ArgumentCaptor.forClass(Income.class);
        verify(incomeRepo).save(interest.capture());
        assertThat(interest.getValue().getAmount()).isEqualByComparingTo("762");
        assertThat(interest.getValue().getIncomeDate()).isEqualTo(maturity);
    }

    @Test
    @DisplayName("closing an FD that is already closed books nothing a second time")
    void closingTwiceIsRefused() {
        when(fdRepo.findByIdAndUserId(9L, USER)).thenReturn(Optional.of(fd("CLOSED", LocalDate.now().minusDays(20))));

        assertThatThrownBy(() -> service.closeFd(9L, USER, new BigDecimal("12639")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("already closed");
        verify(incomeRepo, never()).save(any());
    }

    @Test
    @DisplayName("an FD rolled into a renewal cannot be closed — its money moved to the new FD")
    void renewedFdCannotBeClosed() {
        when(fdRepo.findByIdAndUserId(9L, USER)).thenReturn(Optional.of(fd("MATURED_RENEWED", LocalDate.now().minusDays(20))));

        assertThatThrownBy(() -> service.closeFd(9L, USER, null)).isInstanceOf(ResponseStatusException.class);
        verify(incomeRepo, never()).save(any());
    }
}
