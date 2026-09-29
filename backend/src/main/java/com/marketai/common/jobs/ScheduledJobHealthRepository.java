package com.marketai.common.jobs;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledJobHealthRepository extends JpaRepository<ScheduledJobHealth, String> {
}
