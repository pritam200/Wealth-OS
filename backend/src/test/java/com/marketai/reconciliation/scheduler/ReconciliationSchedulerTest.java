package com.marketai.reconciliation.scheduler;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.reconciliation.dto.ReconciliationReportDto;
import com.marketai.reconciliation.service.ReconciliationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * {@code ReconciliationService.checkAll} previously only ran when a user opened the report
 * screen (GET /api/reconciliation/report) — a real HIGH-severity finding could sit unseen
 * indefinitely if nobody happened to look. This confirms the scheduled sweep actually calls
 * {@code checkAll} for every user, and that one user's failure doesn't stop the rest from
 * being checked.
 */
class ReconciliationSchedulerTest {

    @Test
    void sweepsEveryUserAndSurvivesAPerUserFailure() {
        UserRepository userRepo = mock(UserRepository.class);
        ReconciliationService reconciliationService = mock(ReconciliationService.class);

        User u1 = new User(); u1.setId(1L);
        User u2 = new User(); u2.setId(2L);
        when(userRepo.findAll()).thenReturn(List.of(u1, u2));

        when(reconciliationService.checkAll(1L)).thenThrow(new RuntimeException("boom"));
        when(reconciliationService.checkAll(2L)).thenReturn(ReconciliationReportDto.builder()
            .issueCount(1)
            .issues(List.of(ReconciliationIssue.builder()
                .domain("LEDGER").type("LEDGER_UNCREDITED_PROCEEDS").severity("HIGH")
                .description("test finding").build()))
            .build());

        new ReconciliationScheduler(userRepo, reconciliationService).scheduledReconciliationSweep();

        verify(reconciliationService).checkAll(1L);
        verify(reconciliationService).checkAll(2L);
    }
}
