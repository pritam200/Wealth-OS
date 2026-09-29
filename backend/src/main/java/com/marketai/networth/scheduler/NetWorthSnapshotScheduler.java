package com.marketai.networth.scheduler;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.common.jobs.JobHealthRecorder;
import com.marketai.networth.service.NetWorthService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records every user's net worth once a day. Snapshots used to be written only when the planning
 * page was opened, so "how has my net worth changed this month" often had nothing to compare
 * against. Each snapshot is the canonical calculation ({@link NetWorthService#record}).
 */
@Component
@RequiredArgsConstructor
public class NetWorthSnapshotScheduler {

    private final NetWorthService netWorthService;
    private final UserRepository userRepository;
    private final JobHealthRecorder jobHealth;

    @Scheduled(cron = "0 30 23 * * *", zone = "Asia/Kolkata")
    public void snapshotAll() {
        jobHealth.record("networth-snapshot", run -> {
            for (Long userId : userRepository.findAll().stream().map(User::getId).toList()) {
                try {
                    netWorthService.record(userId);
                } catch (Exception e) {
                    run.failed("user " + userId, e);
                }
            }
        });
    }
}
