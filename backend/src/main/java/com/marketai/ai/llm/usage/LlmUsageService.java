package com.marketai.ai.llm.usage;

import com.marketai.ai.llm.LlmTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Records and summarises {@link LlmCallLog} — the "LLM Processing" diagnostics. */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmUsageService {

    static final int RETENTION_DAYS = 90;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LlmCallLogRepository repo;

    /**
     * Its own transaction: the call happened whatever becomes of the caller's work (an import that
     * rolls back still made the call), and a logging failure must never fail the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(LlmTask task, String provider, String model, String promptVersion, long durationMs,
                       boolean success, String errorCategory, boolean fallback,
                       Integer promptTokens, Integer completionTokens) {
        try {
            repo.save(LlmCallLog.builder()
                .createdAt(LocalDateTime.now(IST))
                .task(task.name()).provider(provider).model(model).promptVersion(promptVersion)
                .durationMs(durationMs).success(success).errorCategory(errorCategory).fallback(fallback)
                .promptTokens(promptTokens).completionTokens(completionTokens)
                .build());
        } catch (Exception e) {
            log.debug("Could not record LLM call metadata: {}", e.getClass().getSimpleName());
        }
    }

    public record Group(String task, String provider, String model, long requests, long successful,
                        long failed, long fallbacks, Long averageLatencyMs) {}

    public record Event(LocalDateTime at, String task, String provider, String model, String errorCategory) {}

    public record Summary(long requests, long successful, long failed, long fallbacks, Long averageLatencyMs,
                          Event lastSuccess, Event lastError, List<Group> groups) {}

    /** Today's calls (India time), overall and per task/provider/model. */
    @Transactional(readOnly = true)
    public Summary today() {
        LocalDateTime since = LocalDate.now(IST).atStartOfDay();
        List<Group> groups = new ArrayList<>();
        long requests = 0, ok = 0, fallbacks = 0, okLatency = 0;
        for (Object[] r : repo.summarise(since)) {
            long n = ((Number) r[3]).longValue();
            long s = r[4] == null ? 0 : ((Number) r[4]).longValue();
            long f = r[5] == null ? 0 : ((Number) r[5]).longValue();
            Long avg = r[6] == null ? null : Math.round(((Number) r[6]).doubleValue());
            groups.add(new Group((String) r[0], (String) r[1], (String) r[2], n, s, n - s, f, avg));
            requests += n;
            ok += s;
            fallbacks += f;
            okLatency += r[7] == null ? 0 : ((Number) r[7]).longValue();
        }
        groups.sort((a, b) -> Long.compare(b.requests(), a.requests()));
        return new Summary(requests, ok, requests - ok, fallbacks, ok == 0 ? null : Math.round((double) okLatency / ok),
            repo.findFirstBySuccessTrueOrderByCreatedAtDesc().map(LlmUsageService::event).orElse(null),
            repo.findFirstBySuccessFalseOrderByCreatedAtDesc().map(LlmUsageService::event).orElse(null),
            groups);
    }

    private static Event event(LlmCallLog l) {
        return new Event(l.getCreatedAt(), l.getTask(), l.getProvider(), l.getModel(), l.getErrorCategory());
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void purgeOld() {
        int removed = repo.deleteOlderThan(LocalDateTime.now(IST).minusDays(RETENTION_DAYS));
        if (removed > 0) log.info("Removed {} LLM call log rows older than {} days", removed, RETENTION_DAYS);
    }
}
