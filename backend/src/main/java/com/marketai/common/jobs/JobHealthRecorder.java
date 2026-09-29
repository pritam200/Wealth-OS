package com.marketai.common.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Records each run of a scheduled job, so a job that keeps failing is visible in the app instead
 * of only in a server log. Recording never breaks the job itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobHealthRecorder {

    private final ScheduledJobHealthRepository repo;

    /** Collects per-item failures inside one run (one user, one scheme) without stopping it. */
    public static final class Run {
        private final List<String> failures = new ArrayList<>();

        public void failed(String what, Exception e) {
            failures.add(what + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    public interface Body {
        void run(Run run) throws Exception;
    }

    /** Runs {@code body} and records its outcome under {@code jobName}. A throw is recorded and logged, not propagated — a scheduled job has no caller to hand it to. */
    public void record(String jobName, Body body) {
        LocalDateTime started = LocalDateTime.now();
        Run run = new Run();
        Exception thrown = null;
        try {
            body.run(run);
        } catch (Exception e) {
            thrown = e;
        }
        String status = thrown != null ? "FAILED" : run.failures.isEmpty() ? "SUCCEEDED" : "PARTIAL";
        String error = thrown != null ? String.valueOf(thrown.getMessage())
            : run.failures.isEmpty() ? null
            : run.failures.size() + " failure(s): " + String.join("; ", run.failures);
        save(jobName, started, status, error);
        if (thrown != null) {
            log.error("Scheduled job {} failed: {}", jobName, thrown.getMessage(), thrown);
        } else if (error != null) {
            log.warn("Scheduled job {} finished with {}", jobName, error);
        }
    }

    private void save(String jobName, LocalDateTime started, String status, String error) {
        try {
            ScheduledJobHealth h = repo.findById(jobName).orElseGet(() -> ScheduledJobHealth.builder().jobName(jobName).build());
            h.setLastStartedAt(started);
            h.setLastFinishedAt(LocalDateTime.now());
            h.setLastStatus(status);
            if (error != null) {
                h.setLastFailureAt(LocalDateTime.now());
                h.setLastError(error.length() > 1000 ? error.substring(0, 997) + "..." : error);
                h.setConsecutiveFailures(h.getConsecutiveFailures() + 1);
            } else {
                h.setConsecutiveFailures(0);
            }
            repo.save(h);
        } catch (Exception e) {
            log.warn("Could not record the run of {}: {}", jobName, e.getMessage());
        }
    }
}
