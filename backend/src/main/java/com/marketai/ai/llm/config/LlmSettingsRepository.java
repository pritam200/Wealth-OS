package com.marketai.ai.llm.config;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmSettingsRepository extends JpaRepository<LlmSettings, Long> {
}
