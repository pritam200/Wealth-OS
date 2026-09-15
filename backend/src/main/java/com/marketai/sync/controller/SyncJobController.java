package com.marketai.sync.controller;

import com.marketai.auth.entity.User;
import com.marketai.sync.entity.SyncJob;
import com.marketai.sync.entity.SyncJobType;
import com.marketai.sync.entity.SyncTrigger;
import com.marketai.sync.service.SyncJobService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Async sync API.
 *
 * The existing POST /api/gmail/sync runs inline and is kept for compatibility, but a full scan
 * is minutes of work and will outlive the request. These endpoints hand back a job id
 * immediately so the UI can show real progress instead of a spinner that may already be dead.
 */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncJobController {

    private final SyncJobService jobService;

    @Data @Builder
    public static class SyncJobDto {
        private Long id;
        private String type;
        private String status;
        private String trigger;
        private Integer itemsTotal;
        private Integer itemsProcessed;
        private Integer attempts;
        private String resultSummary;
        private String lastError;
        private LocalDateTime createdAt;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;

        static SyncJobDto from(SyncJob j) {
            return SyncJobDto.builder()
                .id(j.getId())
                .type(j.getType().name())
                .status(j.getStatus().name())
                .trigger(j.getTrigger().name())
                .itemsTotal(j.getItemsTotal())
                .itemsProcessed(j.getItemsProcessed())
                .attempts(j.getAttempts())
                .resultSummary(j.getResultSummary())
                .lastError(j.getLastError())
                .createdAt(j.getCreatedAt())
                .startedAt(j.getStartedAt())
                .finishedAt(j.getFinishedAt())
                .build();
        }
    }

    /**
     * Queues a sync. Defaults to incremental — the runner falls back to a full scan on its own
     * if no usable watermark exists, so callers don't have to know which mode applies.
     */
    @PostMapping("/gmail")
    public ResponseEntity<SyncJobDto> queueGmailSync(@AuthenticationPrincipal User user,
                                                     @RequestParam(defaultValue = "false") boolean full,
                                                     @RequestParam(required = false) String lookback) {
        SyncJob job = jobService.enqueue(
            user,
            full ? SyncJobType.GMAIL_FULL_SYNC : SyncJobType.GMAIL_INCREMENTAL_SYNC,
            SyncTrigger.MANUAL,
            lookback);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(SyncJobDto.from(job));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<SyncJobDto> get(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return jobService.get(id, user.getId())
            .map(j -> ResponseEntity.ok(SyncJobDto.from(j)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<SyncJobDto>> recent(@AuthenticationPrincipal User user,
                                                   @RequestParam(defaultValue = "10") int limit) {
        List<SyncJobDto> jobs = jobService.recentFor(user.getId(), Math.min(limit, 50))
            .stream().map(SyncJobDto::from).collect(Collectors.toList());
        return ResponseEntity.ok(jobs);
    }
}
