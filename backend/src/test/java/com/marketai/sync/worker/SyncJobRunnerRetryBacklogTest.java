package com.marketai.sync.worker;

import com.marketai.auth.entity.User;
import com.marketai.gmail.dto.GmailSyncResult;
import com.marketai.gmail.repository.GmailTokenRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import com.marketai.gmail.service.GmailClientService;
import com.marketai.gmail.service.GmailIncrementalSyncService;
import com.marketai.gmail.service.GmailSyncService;
import com.marketai.sync.entity.SyncJob;
import com.marketai.sync.entity.SyncJobType;
import com.marketai.sync.repository.SyncJobRepository;
import com.marketai.sync.service.SyncJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@code GMAIL_RETRY_FAILED} used to fall through to {@code runFull} — a fixed 14-day window
 * scan that can never reach a message that first failed months ago. It now looks up every
 * still-retryable message id directly (no date window) and replays them through
 * {@code syncSpecificMessages}, the same path the incremental sync already uses.
 */
class SyncJobRunnerRetryBacklogTest {

    private static final Long USER_ID = 7L;
    private static final Long JOB_ID = 42L;

    private SyncJobRepository jobRepo;
    private SyncJobService jobService;
    private ProcessedEmailRepository processedEmailRepo;
    private GmailSyncService gmailSyncService;
    private SyncJobRunner runner;

    @BeforeEach
    void setUp() {
        jobRepo = mock(SyncJobRepository.class);
        jobService = mock(SyncJobService.class);
        processedEmailRepo = mock(ProcessedEmailRepository.class);
        gmailSyncService = mock(GmailSyncService.class);

        runner = new SyncJobRunner(jobRepo, jobService, mock(GmailTokenRepository.class),
            mock(GmailClientService.class), gmailSyncService, mock(GmailIncrementalSyncService.class),
            processedEmailRepo, mock(com.marketai.reconciliation.service.ReconciliationIssueService.class),
            new com.marketai.common.jobs.JobHealthRecorder(mock(com.marketai.common.jobs.ScheduledJobHealthRepository.class)));

        User user = new User();
        user.setId(USER_ID);
        SyncJob job = SyncJob.builder().id(JOB_ID).user(user).type(SyncJobType.GMAIL_RETRY_FAILED).build();
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job));
    }

    @Test
    @DisplayName("a retry-failed job replays exactly the backlog message ids, not a window rescan")
    void retryFailedJobReplaysBacklogIdsDirectly() {
        when(processedEmailRepo.findRetryableMessageIds(USER_ID))
            .thenReturn(List.of("msg-failed-1", "msg-review-2"));
        when(gmailSyncService.syncSpecificMessages(eq(USER_ID), anyList()))
            .thenReturn(GmailSyncResult.builder().imported(1).skipped(1).failed(0).build());

        runner.run(JOB_ID);

        verify(gmailSyncService).syncSpecificMessages(USER_ID, List.of("msg-failed-1", "msg-review-2"));
        verify(gmailSyncService, never()).syncForUser(any(), any());
        verify(jobService).complete(eq(JOB_ID), eq(com.marketai.sync.entity.SyncJobStatus.SUCCEEDED), anyString(), eq(1));
    }

    @Test
    @DisplayName("an empty backlog succeeds as a no-op instead of falling back to a full scan")
    void emptyBacklogIsANoOp() {
        when(processedEmailRepo.findRetryableMessageIds(USER_ID)).thenReturn(List.of());

        runner.run(JOB_ID);

        verify(gmailSyncService, never()).syncSpecificMessages(any(), any());
        verify(jobService).complete(eq(JOB_ID), eq(com.marketai.sync.entity.SyncJobStatus.SUCCEEDED), anyString(), eq(0));
    }

    private static GmailSyncResult result(int failed, String reconciliationStatus) {
        return GmailSyncResult.builder().imported(3).failed(failed)
            .reconciliation(GmailSyncResult.ReconciliationReport.builder().status(reconciliationStatus).build())
            .build();
    }

    @Test
    @DisplayName("a run that failed some emails, or left something for the user, is not reported as a success")
    void outcomeReflectsWhatActuallyHappened() {
        org.assertj.core.api.Assertions.assertThat(SyncJobRunner.outcomeOf(result(0, "OK")))
            .isEqualTo(com.marketai.sync.entity.SyncJobStatus.SUCCEEDED);
        org.assertj.core.api.Assertions.assertThat(SyncJobRunner.outcomeOf(result(2, "ACTION_REQUIRED")))
            .isEqualTo(com.marketai.sync.entity.SyncJobStatus.PARTIAL_SUCCESS);
        org.assertj.core.api.Assertions.assertThat(SyncJobRunner.outcomeOf(result(0, "ACTION_REQUIRED")))
            .isEqualTo(com.marketai.sync.entity.SyncJobStatus.RECONCILIATION_REQUIRED);
    }
}
