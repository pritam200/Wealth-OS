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
            processedEmailRepo);

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
        verify(jobService).succeed(eq(JOB_ID), anyString(), eq(1));
    }

    @Test
    @DisplayName("an empty backlog succeeds as a no-op instead of falling back to a full scan")
    void emptyBacklogIsANoOp() {
        when(processedEmailRepo.findRetryableMessageIds(USER_ID)).thenReturn(List.of());

        runner.run(JOB_ID);

        verify(gmailSyncService, never()).syncSpecificMessages(any(), any());
        verify(jobService).succeed(eq(JOB_ID), anyString(), eq(0));
    }
}
