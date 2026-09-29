package com.marketai.ai.llm.config;

import com.marketai.ai.llm.LlmTask;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * The saved LLM configuration — one row (id 1) for the whole installation, since the provider
 * decides where every user's documents are sent. Absent until an admin first saves; until then
 * the application.yml / environment defaults apply (see {@link LlmConfigService}).
 *
 * <p>The Gemini API key is stored AES-256/GCM encrypted ({@code PasswordCipher}) and is never
 * returned by the API — only whether one is set and its last four characters.
 */
@Entity
@Table(name = "llm_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LlmSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** LOCAL | CLOUD | HYBRID | CUSTOM — see {@link PrivacyMode}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "privacy_mode", length = 12, nullable = false)
    private PrivacyMode privacyMode;

    @Column(name = "ai_enabled", nullable = false)
    private boolean aiEnabled;

    @Column(name = "fallback_enabled", nullable = false)
    private boolean fallbackEnabled;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "gemini_api_key_enc", columnDefinition = "text")
    private String geminiApiKeyEncrypted;

    @Column(name = "gemini_api_key_last4", length = 4)
    private String geminiApiKeyLast4;

    @Column(name = "gemini_model", length = 80)
    private String geminiModel;

    @Column(name = "gemini_temperature")
    private Double geminiTemperature;

    @Column(name = "gemini_max_tokens")
    private Integer geminiMaxTokens;

    @Column(name = "gemini_timeout_seconds")
    private Integer geminiTimeoutSeconds;

    @Column(name = "ollama_base_url", length = 200)
    private String ollamaBaseUrl;

    @Column(name = "ollama_model", length = 80)
    private String ollamaModel;

    @Column(name = "ollama_temperature")
    private Double ollamaTemperature;

    @Column(name = "ollama_max_tokens")
    private Integer ollamaMaxTokens;

    @Column(name = "ollama_timeout_seconds")
    private Integer ollamaTimeoutSeconds;

    /** Provider and model per task; a task with no row uses the privacy mode's default. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "llm_task_routes", joinColumns = @JoinColumn(name = "settings_id"))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "task", length = 40)
    @Builder.Default
    private Map<LlmTask, TaskRouteColumns> routes = new EnumMap<>(LlmTask.class);

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Embeddable
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class TaskRouteColumns {
        /** Marks a task switched off. Never stored as null: Hibernate reads an all-null embeddable
         *  as a missing map entry, and the next save would insert the row a second time. */
        public static final String OFF = "OFF";

        /** "GEMINI", "OLLAMA" or {@link #OFF}. */
        @Column(name = "provider", length = 12)
        private String provider;

        public static TaskRouteColumns off() { return new TaskRouteColumns(OFF, null); }

        /** Null means the provider's default model. */
        @Column(name = "model", length = 80)
        private String model;
    }
}
