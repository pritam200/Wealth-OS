package com.marketai.reconciliation.scheduler;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.reconciliation.dto.ReconciliationReportDto;
import com.marketai.reconciliation.service.ReconciliationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    void sweepsEveryUserAndRecordsAPerUserFailureWithoutStopping() {
        UserRepository userRepo = mock(UserRepository.class);
        com.marketai.reconciliation.service.ReconciliationIssueService issueService =
            mock(com.marketai.reconciliation.service.ReconciliationIssueService.class);
        com.marketai.common.jobs.ScheduledJobHealthRepository healthRepo =
            mock(com.marketai.common.jobs.ScheduledJobHealthRepository.class);
        when(healthRepo.findById(any())).thenReturn(java.util.Optional.empty());

        User u1 = new User(); u1.setId(1L);
        User u2 = new User(); u2.setId(2L);
        when(userRepo.findAll()).thenReturn(List.of(u1, u2));
        when(issueService.refresh(1L)).thenThrow(new RuntimeException("boom"));
        when(issueService.refresh(2L)).thenReturn(List.of());

        new ReconciliationScheduler(userRepo, issueService, new com.marketai.common.jobs.JobHealthRecorder(healthRepo))
            .scheduledReconciliationSweep();

        verify(issueService).refresh(1L);
        verify(issueService).refresh(2L);
        // The failure is kept where the Reconciliation Center can show it, not only logged.
        verify(healthRepo).save(argThat(h -> "PARTIAL".equals(h.getLastStatus())
            && h.getLastError().contains("user 1") && h.getLastError().contains("boom")));
    }
}
