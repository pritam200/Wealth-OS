package com.marketai.ai.llm;

import com.marketai.ai.client.GeminiClient;
import com.marketai.ai.llm.config.LlmConfigService;
import com.marketai.ai.llm.config.LlmConfigService.Route;
import com.marketai.ai.llm.config.LlmConfigService.Snapshot;
import com.marketai.ai.llm.usage.LlmUsageService;
import com.marketai.ai.prompt.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The single entry point for model calls. Callers name a {@link LlmTask} and a
 * {@link PromptTemplate}; which provider and model answer — and what happens when they can't —
 * is decided here from {@link LlmConfigService}, so no business code branches on a provider.
 *
 * <p>For each call: the task's route is checked against the privacy mode and the model's
 * capabilities, the call is retried on transient failures ({@code maxRetries}), and only if
 * fallback is enabled is the other provider tried. Every attempt is logged as metadata
 * ({@link LlmUsageService}); the completion says which provider, model and prompt version
 * produced it and whether it was a fallback. Whatever answers, the caller's validation is the
 * same — this class returns text, never a financial record.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final LlmConfigService config;
    private final LlmModelCatalog catalog;
    private final LlmUsageService usage;

    /** Surfaced to users when an AI feature is asked for but nothing can serve it. */
    public static final String NONE_AVAILABLE =
        "No AI model is available for this. Configure one in Settings → LLM Configuration "
        + "(a local Ollama model, or a Gemini API key).";

    public static final String NO_VISION =
        "Reading scanned documents needs an image-capable model: assign one to \"Scanned document reading\" "
        + "in Settings → LLM Configuration.";

    private static final long RETRY_BACKOFF_MS = 500;

    /**
     * @throws LlmUnavailableException when no configured provider could answer — callers must
     *         treat that as "no answer", never as an empty one.
     */
    public LlmCompletion complete(LlmTask task, PromptTemplate prompt, String userPrompt) {
        return run(task, prompt, userPrompt, List.of());
    }

    /** Reads text out of images (scanned pages) with the model assigned to scan reading. */
    public LlmCompletion transcribe(PromptTemplate prompt, String userPrompt, List<GeminiClient.InlineImage> images) {
        return run(LlmTask.DOCUMENT_TRANSCRIPTION, prompt, userPrompt, images);
    }

    /** Configured to be served (no network check — a failed call reports itself). */
    public boolean isAvailable(LlmTask task) {
        Snapshot s = config.current();
        if (!s.aiEnabled()) return false;
        return usable(s, s.route(task)) || s.fallback(task).map(r -> usable(s, r)).orElse(false);
    }

    /** Whether a scan can be read: a configured route whose model is not known to lack vision. */
    public boolean canReadImages() {
        Snapshot s = config.current();
        if (!s.aiEnabled()) return false;
        return readsImages(s, s.route(LlmTask.DOCUMENT_TRANSCRIPTION))
            || s.fallback(LlmTask.DOCUMENT_TRANSCRIPTION).map(r -> readsImages(s, r)).orElse(false);
    }

    /** "gemini:gemini-2.5-flash" — the route a task would use, for display and audit notes. */
    public String describe(LlmTask task) {
        Route r = config.current().route(task);
        return r.enabled() ? r.provider().key() + ":" + r.model() : "none";
    }

    private boolean usable(Snapshot s, Route r) {
        return r.enabled() && s.permits(r.provider()) && s.provider(r.provider()).configured();
    }

    private boolean readsImages(Snapshot s, Route r) {
        if (!usable(s, r)) return false;
        Optional<ModelInfo> info = catalog.find(s.provider(r.provider()).withModel(r.model()), r.model());
        // Unknown on Gemini: its chat models read images, and a failed call reports itself.
        return info.map(m -> m.capabilities().contains(LlmCapability.VISION))
            .orElse(r.provider() == LlmProviderId.GEMINI);
    }

    private LlmCompletion run(LlmTask task, PromptTemplate prompt, String userPrompt,
                              List<GeminiClient.InlineImage> images) {
        Snapshot s = config.current();
        if (!s.aiEnabled()) {
            throw new LlmUnavailableException(LlmErrorCategory.NOT_CONFIGURED, "AI is switched off in LLM Configuration");
        }
        Route primary = s.route(task);
        LlmUnavailableException failure = null;
        if (primary.enabled()) {
            try {
                return attempt(s, primary, task, prompt, userPrompt, images, false);
            } catch (LlmUnavailableException e) {
                failure = e;
            }
        }
        Optional<Route> fallback = s.fallback(task);
        if (fallback.isPresent()) {
            Route r = fallback.get();
            log.info("LLM route for {} failed ({}) — falling back to {}:{}", task,
                failure != null ? failure.getCategory() : "task not routed", r.provider().key(), r.model());
            return attempt(s, r, task, prompt, userPrompt, images, true);
        }
        if (failure != null) throw failure;
        throw new LlmUnavailableException(LlmErrorCategory.NOT_CONFIGURED,
            task == LlmTask.DOCUMENT_TRANSCRIPTION ? NO_VISION : NONE_AVAILABLE);
    }

    private LlmCompletion attempt(Snapshot s, Route route, LlmTask task, PromptTemplate prompt, String userPrompt,
                                  List<GeminiClient.InlineImage> images, boolean fallback) {
        ProviderSettings settings = s.provider(route.provider()).withModel(route.model());
        String providerKey = route.provider().key();
        try {
            if (!s.permits(route.provider())) {
                throw new LlmUnavailableException(LlmErrorCategory.BLOCKED_BY_PRIVACY,
                    providerKey + " is a cloud provider and the privacy mode is Local AI");
            }
            if (!settings.configured()) {
                throw new LlmUnavailableException(LlmErrorCategory.NOT_CONFIGURED, providerKey + " is not configured");
            }
            requireCapabilities(settings, route, task);
        } catch (LlmUnavailableException e) {
            usage.record(task, providerKey, route.model(), prompt.tag(), 0, false, e.getCategory().name(), fallback, null, null);
            throw e;
        }

        LlmRequest request = new LlmRequest(prompt.system(), userPrompt, route.model(), task.structured(),
            task.deterministic() ? 0.0 : settings.temperature(), settings.maxTokens(), settings.timeoutSeconds(), images);
        LlmProvider provider = catalog.provider(route.provider());
        for (int attempt = 0; ; attempt++) {
            long started = System.currentTimeMillis();
            try {
                LlmCompletion c = provider.generate(settings, request);
                usage.record(task, providerKey, route.model(), prompt.tag(), System.currentTimeMillis() - started,
                    true, null, fallback, c.getPromptTokens(), c.getCompletionTokens());
                return c.toBuilder().provider(providerKey).model(route.model())
                    .promptVersion(prompt.tag()).fallbackUsed(fallback).build();
            } catch (LlmUnavailableException e) {
                usage.record(task, providerKey, route.model(), prompt.tag(), System.currentTimeMillis() - started,
                    false, e.getCategory().name(), fallback, null, null);
                // Rate limits are already backed off inside the Gemini client.
                boolean retry = e.getCategory().retryable() && e.getCategory() != LlmErrorCategory.RATE_LIMITED
                    && attempt < s.maxRetries();
                if (!retry) throw e;
                sleep(RETRY_BACKOFF_MS * (attempt + 1));
            }
        }
    }

    /**
     * Refuses a model known to lack what the task needs. A local model whose capabilities could
     * not be read is not trusted to read images.
     */
    private void requireCapabilities(ProviderSettings settings, Route route, LlmTask task) {
        Optional<ModelInfo> info = catalog.find(settings, route.model());
        if (info.isPresent() && !info.get().supports(task.requires())) {
            throw new LlmUnavailableException(LlmErrorCategory.CAPABILITY_MISSING,
                route.model() + " cannot do " + task.label() + " (needs " + task.requires() + ")");
        }
        if (info.isEmpty() && route.provider() == LlmProviderId.OLLAMA && task.requires().contains(LlmCapability.VISION)) {
            throw new LlmUnavailableException(LlmErrorCategory.CAPABILITY_MISSING,
                "Could not confirm that " + route.model() + " reads images");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException(LlmErrorCategory.PROVIDER_ERROR, "Interrupted while retrying");
        }
    }
}
