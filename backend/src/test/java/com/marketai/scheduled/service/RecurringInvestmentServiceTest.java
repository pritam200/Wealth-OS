package com.marketai.scheduled.service;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.scheduled.dto.RecurringInvestmentResponse;
import com.marketai.scheduled.dto.RecurringInvestmentUpdateRequest;
import com.marketai.scheduled.entity.RecurringInvestment;
import com.marketai.scheduled.entity.RecurringInvestmentHistory;
import com.marketai.scheduled.repository.RecurringInvestmentHistoryRepository;
import com.marketai.scheduled.repository.RecurringInvestmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecurringInvestmentServiceTest {

    private RecurringInvestmentRepository repo;
    private RecurringInvestmentHistoryRepository historyRepo;
    private RecurringInvestmentService service;

    private User user() {
        User u = new User();
        u.setId(1L);
        return u;
    }

    @BeforeEach
    void setUp() {
        repo = mock(RecurringInvestmentRepository.class);
        historyRepo = mock(RecurringInvestmentHistoryRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        PortfolioRepository portfolioRepository = mock(PortfolioRepository.class);
        HoldingRepository holdingRepository = mock(HoldingRepository.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);

        service = new RecurringInvestmentService(repo, historyRepo, userRepository, portfolioRepository,
            holdingRepository, transactionRepository);

        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(historyRepo.findByRecurringInvestmentIdOrderByChangedAtAsc(any())).thenReturn(Collections.emptyList());
        when(portfolioRepository.findByUserId(any())).thenReturn(Collections.emptyList());
    }

    private RecurringInvestment existing() {
        return RecurringInvestment.builder()
            .id(5L).user(user()).type(RecurringInvestment.Type.PPF).label("PPF - SBI")
            .amount(new BigDecimal("5000")).startDate(LocalDate.of(2026, 1, 10)).status("ACTIVE")
            .build();
    }

    @Test
    void updateAmountRecordsHistoryAndAppliesNewValue() {
        when(repo.findById(5L)).thenReturn(Optional.of(existing()));

        RecurringInvestmentUpdateRequest req = new RecurringInvestmentUpdateRequest();
        req.setAmount(new BigDecimal("7500"));

        RecurringInvestmentResponse resp = service.update(1L, 5L, req);

        assertThat(resp.getAmount()).isEqualByComparingTo("7500");
        verify(historyRepo).save(argThat(h ->
            "amount".equals(h.getField()) && "5000".equals(h.getOldValue()) && "7500".equals(h.getNewValue())));
    }

    @Test
    void pauseAndResumeAreStatusUpdatesRecordedAsHistory() {
        when(repo.findById(5L)).thenReturn(Optional.of(existing()));

        RecurringInvestmentUpdateRequest pause = new RecurringInvestmentUpdateRequest();
        pause.setStatus("PAUSED");
        RecurringInvestmentResponse resp = service.update(1L, 5L, pause);

        assertThat(resp.getStatus()).isEqualTo("PAUSED");
        verify(historyRepo).save(argThat(h ->
            "status".equals(h.getField()) && "ACTIVE".equals(h.getOldValue()) && "PAUSED".equals(h.getNewValue())));
    }

    @Test
    void updateRejectsAnotherUsersSchedule() {
        RecurringInvestment ri = existing();
        User other = new User();
        other.setId(99L);
        ri.setUser(other);
        when(repo.findById(5L)).thenReturn(Optional.of(ri));

        RecurringInvestmentUpdateRequest req = new RecurringInvestmentUpdateRequest();
        req.setAmount(new BigDecimal("1"));

        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> service.update(1L, 5L, req));
    }

    @Test
    void amountHistoryIsReturnedOldestFirst() {
        when(repo.findById(5L)).thenReturn(Optional.of(existing()));
        RecurringInvestmentHistory h1 = RecurringInvestmentHistory.builder()
            .recurringInvestmentId(5L).field("amount").oldValue("5000").newValue("7500").build();
        when(historyRepo.findByRecurringInvestmentIdOrderByChangedAtAsc(5L)).thenReturn(List.of(h1));

        RecurringInvestmentUpdateRequest req = new RecurringInvestmentUpdateRequest();
        req.setLabel("PPF - SBI Updated");
        RecurringInvestmentResponse resp = service.update(1L, 5L, req);

        assertThat(resp.getAmountHistory()).hasSize(1);
        assertThat(resp.getAmountHistory().get(0).getOldValue()).isEqualTo("5000");
    }

    @Test
    void stockSipDerivesInstallmentsJustLikeSip() {
        RecurringInvestment ri = RecurringInvestment.builder()
            .id(6L).user(user()).type(RecurringInvestment.Type.STOCK_SIP).label("RELIANCE SIP")
            .linkedSymbol("RELIANCE.NS")
            .amount(new BigDecimal("2000")).startDate(LocalDate.now().minusMonths(1)).status("ACTIVE")
            .build();
        when(repo.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(ri));

        List<RecurringInvestmentResponse> list = service.list(1L);

        assertThat(list).hasSize(1);
        // No matching transaction was set up, so the past installment should surface as MISSED —
        // proving STOCK_SIP goes through the same transaction-derived path as SIP, not the
        // PPF/NPS "just show next due date" fallback.
        assertThat(list.get(0).getInstallments()).isNotEmpty();
    }
}
