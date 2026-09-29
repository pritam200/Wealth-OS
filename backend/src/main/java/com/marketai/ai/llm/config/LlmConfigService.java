package com.marketai.ai.llm.config;

import com.marketai.ai.llm.LlmProviderId;
import com.marketai.ai.llm.LlmTask;
import com.marketai.ai.llm.ProviderSettings;
import com.marketai.gmail.security.PasswordCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one place that decides which provider and model serve each task, with what endpoint,
 * credentials, temperature, token cap, timeout, retries and fallback. Nothing else reads LLM
 * settings — business code asks {@code LlmService} for a task and never sees a provider name.
 *
 * <p>Settings come from the saved {@link LlmSettings} row; until an admin first saves, from the
 * application.yml / environment values (app.llm.*, app.gemini.*), so an existing deployment keeps
 * behaving exactly as before. A key saved here takes precedence over GEMINI_API_KEY.
 */
@Service
@Slf4j
public class LlmConfigService {

    private final LlmSettingsRepository repo;
    private final PasswordCipher cipher;

    // Environment defaults — used until the configuration is first saved.
    @Value("${app.llm.provider:ollama}") String envProvider = "ollama";
    @Value("${app.llm.email-provider:}") String envEmailProvider = "";
    @Value("${app.llm.fallback-enabled:true}") boolean envFallback = true;
    @Value("${app.gemini.api-key:}") String envGeminiKey = "";
    @Value("${app.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta";
    @Value("${app.gemini.model:gemini-2.5-flash}") String envGeminiModel = "gemini-2.5-flash";
    @Value("${app.llm.ollama.base-url:http://localhost:11434}") String envOllamaUrl = "http://localhost:11434";
    @Value("${app.llm.ollama.model:qwen2.5:7b}") String envOllamaModel = "qwen2.5:7b";
    @Value("${app.llm.ollama.timeout-seconds:120}") int envOllamaTimeout = 120;
    @Value("${app.llm.ollama.temperature:0.0}") double envOllamaTemperature = 0.0;

    static final double DEFAULT_GEMINI_TEMPERATURE = 0.7;
    static final int DEFAULT_GEMINI_TIMEOUT = 60;
    static final int DEFAULT_RETRIES = 1;

    private volatile Snapshot cached;

    public LlmConfigService(LlmSettingsRepository repo, PasswordCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    public enum KeySource { SAVED, ENVIRONMENT, NONE }

    /** A task's provider and model; provider null means the task is switched off. */
    public record Route(LlmTask task, LlmProviderId provider, String model) {
        public boolean enabled() { return provider != null; }
    }

    /** The resolved configuration. Immutable; replaced as a whole on save. */
    public record Snapshot(boolean aiEnabled, PrivacyMode privacyMode, boolean fallbackEnabled, int maxRetries,
                           ProviderSettings gemini, ProviderSettings ollama, Map<LlmTask, Route> routes,
                           boolean saved, KeySource keySource, String keyLast4, LocalDateTime updatedAt) {

        public ProviderSettings provider(LlmProviderId id) {
            return id == LlmProviderId.GEMINI ? gemini : ollama;
        }

        public Route route(LlmTask task) {
            Route r = routes.get(task);
            return r != null && aiEnabled ? r : new Route(task, null, null);
        }

        /**
         * The provider to try when the task's own one fails — the other provider on its default
         * model — or empty when fallback is off, the other provider isn't configured, or the
         * privacy mode forbids it (Local AI never falls back to the cloud).
         */
        public Optional<Route> fallback(LlmTask task) {
            Route primary = route(task);
            if (!fallbackEnabled || !primary.enabled()) return Optional.empty();
            LlmProviderId other = primary.provider().other();
            if (other.cloud() && !privacyMode.allowsCloud()) return Optional.empty();
            ProviderSettings s = provider(other);
            if (!s.configured()) return Optional.empty();
            return Optional.of(new Route(task, other, s.defaultModel()));
        }

        /** Whether a provider may be used at all under the privacy mode. */
        public boolean permits(LlmProviderId id) {
            return !id.cloud() || privacyMode.allowsCloud();
        }
    }

    public Snapshot current() {
        Snapshot s = cached;
        if (s == null) {
            s = load();
            cached = s;
        }
        return s;
    }

    /** Drops the cached snapshot; the next call re-reads the saved row. */
    public void invalidate() { cached = null; }

    private Snapshot load() {
        LlmSettings row;
        try {
            row = repo.findById(LlmSettings.SINGLETON_ID).orElse(null);
        } catch (Exception e) {
            // Table not there yet (first start before the schema update) — environment defaults.
            log.debug("LLM settings not readable yet: {}", e.getClass().getSimpleName());
            row = null;
        }
        return row == null ? fromEnvironment() : fromRow(row);
    }

    /** What the environment configured before this screen existed. */
    Snapshot fromEnvironment() {
        LlmProviderId main = LlmProviderId.parse(envProvider);
        LlmProviderId email = LlmProviderId.parse(envEmailProvider);
        String envKey = blankToNull(envGeminiKey);
        ProviderSettings gemini = new ProviderSettings(LlmProviderId.GEMINI, geminiBaseUrl, envKey, envGeminiModel,
            DEFAULT_GEMINI_TEMPERATURE, null, DEFAULT_GEMINI_TIMEOUT);
        ProviderSettings ollama = new ProviderSettings(LlmProviderId.OLLAMA, envOllamaUrl, null, envOllamaModel,
            envOllamaTemperature, null, envOllamaTimeout);

        Map<LlmTask, Route> routes = new EnumMap<>(LlmTask.class);
        for (LlmTask t : LlmTask.values()) {
            LlmProviderId p = main == null ? LlmProviderId.OLLAMA : main;
            if (email != null && (t == LlmTask.EMAIL_CLASSIFICATION || t == LlmTask.EMAIL_EXTRACTION
                || t == LlmTask.DOCUMENT_EXTRACTION)) p = email;
            // Scans were only ever read by Gemini, the one configured provider that took images.
            if (t == LlmTask.DOCUMENT_TRANSCRIPTION) p = LlmProviderId.GEMINI;
            routes.put(t, new Route(t, p, (p == LlmProviderId.GEMINI ? gemini : ollama).defaultModel()));
        }
        return new Snapshot(!"none".equalsIgnoreCase(envProvider), PrivacyMode.CUSTOM, envFallback, DEFAULT_RETRIES,
            gemini, ollama, Collections.unmodifiableMap(routes), false,
            envKey != null ? KeySource.ENVIRONMENT : KeySource.NONE, last4(envKey), null);
    }

    Snapshot fromRow(LlmSettings row) {
        String savedKey = decryptKey(row);
        String envKey = blankToNull(envGeminiKey);
        String key = savedKey != null ? savedKey : envKey;
        KeySource source = savedKey != null ? KeySource.SAVED : envKey != null ? KeySource.ENVIRONMENT : KeySource.NONE;

        ProviderSettings gemini = new ProviderSettings(LlmProviderId.GEMINI, geminiBaseUrl, key,
            orDefault(row.getGeminiModel(), envGeminiModel),
            row.getGeminiTemperature() != null ? row.getGeminiTemperature() : DEFAULT_GEMINI_TEMPERATURE,
            row.getGeminiMaxTokens(),
            row.getGeminiTimeoutSeconds() != null ? row.getGeminiTimeoutSeconds() : DEFAULT_GEMINI_TIMEOUT);
        ProviderSettings ollama = new ProviderSettings(LlmProviderId.OLLAMA,
            orDefault(row.getOllamaBaseUrl(), envOllamaUrl), null,
            orDefault(row.getOllamaModel(), envOllamaModel),
            row.getOllamaTemperature() != null ? row.getOllamaTemperature() : envOllamaTemperature,
            row.getOllamaMaxTokens(),
            row.getOllamaTimeoutSeconds() != null ? row.getOllamaTimeoutSeconds() : envOllamaTimeout);

        Map<LlmTask, Route> routes = new EnumMap<>(LlmTask.class);
        for (LlmTask t : LlmTask.values()) {
            LlmSettings.TaskRouteColumns saved = row.getRoutes().get(t);
            LlmProviderId p = saved != null ? LlmProviderId.parse(saved.getProvider()) : row.getPrivacyMode().presetFor(t);
            if (saved == null && p == null) p = LlmProviderId.OLLAMA;
            if (p != null && p.cloud() && !row.getPrivacyMode().allowsCloud()) p = null;
            String model = p == null ? null
                : saved != null && saved.getModel() != null && !saved.getModel().isBlank() ? saved.getModel()
                : (p == LlmProviderId.GEMINI ? gemini : ollama).defaultModel();
            routes.put(t, new Route(t, p, model));
        }
        return new Snapshot(row.isAiEnabled(), row.getPrivacyMode(), row.isFallbackEnabled(), row.getMaxRetries(),
            gemini, ollama, Collections.unmodifiableMap(routes), true, source,
            savedKey != null ? row.getGeminiApiKeyLast4() : last4(envKey), row.getUpdatedAt());
    }

    private String decryptKey(LlmSettings row) {
        if (row.getGeminiApiKeyEncrypted() == null) return null;
        try {
            return cipher.decrypt(row.getGeminiApiKeyEncrypted());
        } catch (Exception e) {
            // The encryption key changed since the API key was saved: treat it as absent rather
            // than failing every call, and say so without the ciphertext.
            log.warn("The saved Gemini API key could not be decrypted (PDF_PASSWORD_ENC_KEY changed?) — re-enter it in LLM Configuration");
            return null;
        }
    }

    /**
     * Saves the configuration. The key is encrypted before it is stored; {@code apiKeyChange}
     * null leaves the saved key as it is, empty removes it.
     */
    @Transactional
    public Snapshot save(LlmSettings incoming, String apiKeyChange, Long userId) {
        LlmSettings row = repo.findById(LlmSettings.SINGLETON_ID).orElseGet(() -> {
            LlmSettings s = new LlmSettings();
            s.setId(LlmSettings.SINGLETON_ID);
            return s;
        });
        row.setPrivacyMode(incoming.getPrivacyMode());
        row.setAiEnabled(incoming.isAiEnabled());
        row.setFallbackEnabled(incoming.isFallbackEnabled());
        row.setMaxRetries(incoming.getMaxRetries());
        row.setGeminiModel(incoming.getGeminiModel());
        row.setGeminiTemperature(incoming.getGeminiTemperature());
        row.setGeminiMaxTokens(incoming.getGeminiMaxTokens());
        row.setGeminiTimeoutSeconds(incoming.getGeminiTimeoutSeconds());
        row.setOllamaBaseUrl(incoming.getOllamaBaseUrl());
        row.setOllamaModel(incoming.getOllamaModel());
        row.setOllamaTemperature(incoming.getOllamaTemperature());
        row.setOllamaMaxTokens(incoming.getOllamaMaxTokens());
        row.setOllamaTimeoutSeconds(incoming.getOllamaTimeoutSeconds());
        // Updated in place: replacing the map made Hibernate insert the new rows before deleting
        // the old ones, which collides on the (settings_id, task) key.
        row.getRoutes().keySet().retainAll(incoming.getRoutes().keySet());
        incoming.getRoutes().forEach((task, route) -> {
            LlmSettings.TaskRouteColumns existing = row.getRoutes().get(task);
            if (existing == null) {
                row.getRoutes().put(task, new LlmSettings.TaskRouteColumns(route.getProvider(), route.getModel()));
            } else if (!existing.equals(route)) {
                existing.setProvider(route.getProvider());
                existing.setModel(route.getModel());
            }
        });
        if (apiKeyChange != null) {
            String key = apiKeyChange.trim();
            if (key.isEmpty()) {
                row.setGeminiApiKeyEncrypted(null);
                row.setGeminiApiKeyLast4(null);
            } else {
                row.setGeminiApiKeyEncrypted(cipher.encrypt(key));
                row.setGeminiApiKeyLast4(last4(key));
            }
        }
        row.setUpdatedAt(LocalDateTime.now());
        row.setUpdatedBy(userId);
        repo.save(row);
        Snapshot s = fromRow(row);
        cached = s;
        log.info("LLM configuration saved by user {}: mode={}, fallback={}", userId, row.getPrivacyMode(), row.isFallbackEnabled());
        return s;
    }

    private static String last4(String key) {
        return key == null || key.length() < 8 ? null : key.substring(key.length() - 4);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String orDefault(String s, String d) {
        return s == null || s.isBlank() ? d : s.trim();
    }
}
