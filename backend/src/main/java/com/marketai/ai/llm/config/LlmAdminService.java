package com.marketai.ai.llm.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketai.ai.llm.*;
import com.marketai.ai.llm.config.LlmConfigService.Route;
import com.marketai.ai.llm.config.LlmConfigService.Snapshot;
import com.marketai.ai.llm.usage.LlmUsageService;
import com.marketai.ai.prompt.PromptLibrary;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Backs Settings → LLM Configuration: the configuration as the screen shows it (the API key only
 * as "set, ending …1234"), validation before saving — including that each task's model can do
 * what the task needs — the connection test, model discovery and health.
 *
 * <p>Errors shown here are {@link LlmErrorCategory} wording, never a provider's raw response.
 */
@Service
@RequiredArgsConstructor
public class LlmAdminService {

    private final LlmConfigService config;
    private final LlmModelCatalog catalog;
    private final LlmUsageService usage;
    private final LlmJsonParser json;

    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9._:/@-]{1,80}");
    private static final String MASK = "••••";

    // ------------------------------------------------------------------ view

    @Value @Builder
    public static class ProviderView {
        String id;
        String label;
        boolean cloud;
        boolean configured;
        String endpoint;
        String model;
        double temperature;
        Integer maxTokens;
        int timeoutSeconds;
        /** Gemini only. */
        boolean apiKeySet;
        /** SAVED, ENVIRONMENT or NONE. */
        String apiKeySource;
        /** "••••abcd" — never more than the last four characters. */
        String apiKeyHint;
    }

    @Value @Builder
    public static class TaskView {
        String task;
        String label;
        List<String> requires;
        boolean structured;
        boolean readsDocuments;
        /** GEMINI, OLLAMA or null (off). */
        String provider;
        String model;
        /** What the privacy mode would pick; null for CUSTOM. */
        String presetProvider;
        /** GEMINI/OLLAMA when a fallback would be tried, null otherwise. */
        String fallbackProvider;
    }

    @Value @Builder
    public static class ConfigView {
        boolean aiEnabled;
        String privacyMode;
        boolean fallbackEnabled;
        int maxRetries;
        boolean saved;
        LocalDateTime updatedAt;
        List<ProviderView> providers;
        List<TaskView> tasks;
        Map<String, String> capabilityLabels;
        List<String> warnings;
    }

    public ConfigView view() {
        return view(config.current(), List.of());
    }

    private ConfigView view(Snapshot s, List<String> warnings) {
        List<TaskView> tasks = new ArrayList<>();
        for (LlmTask t : LlmTask.values()) {
            Route r = s.route(t);
            LlmProviderId preset = s.privacyMode().presetFor(t);
            tasks.add(TaskView.builder()
                .task(t.name()).label(t.label())
                .requires(t.requires().stream().map(Enum::name).toList())
                .structured(t.structured()).readsDocuments(t.readsDocuments())
                .provider(r.enabled() ? r.provider().name() : null)
                .model(r.model())
                .presetProvider(preset != null ? preset.name() : null)
                .fallbackProvider(s.fallback(t).map(f -> f.provider().name()).orElse(null))
                .build());
        }
        Map<String, String> caps = new LinkedHashMap<>();
        for (LlmCapability c : LlmCapability.values()) caps.put(c.name(), c.label());
        return ConfigView.builder()
            .aiEnabled(s.aiEnabled()).privacyMode(s.privacyMode().name())
            .fallbackEnabled(s.fallbackEnabled()).maxRetries(s.maxRetries())
            .saved(s.saved()).updatedAt(s.updatedAt())
            .providers(List.of(providerView(s, s.gemini()), providerView(s, s.ollama())))
            .tasks(tasks).capabilityLabels(caps).warnings(warnings)
            .build();
    }

    private static ProviderView providerView(Snapshot s, ProviderSettings p) {
        boolean gemini = p.id() == LlmProviderId.GEMINI;
        return ProviderView.builder()
            .id(p.id().name()).label(p.id().label()).cloud(p.id().cloud())
            .configured(p.configured())
            .endpoint(gemini ? null : p.endpoint())
            .model(p.defaultModel()).temperature(p.temperature()).maxTokens(p.maxTokens())
            .timeoutSeconds(p.timeoutSeconds())
            .apiKeySet(gemini && p.apiKey() != null)
            .apiKeySource(gemini ? s.keySource().name() : null)
            .apiKeyHint(gemini && s.keyLast4() != null ? MASK + s.keyLast4() : gemini && p.apiKey() != null ? MASK : null)
            .build();
    }

    // ------------------------------------------------------------------ save

    /** What the screen sends. {@code geminiApiKey} null = keep the saved key; {@code removeGeminiApiKey} clears it. */
    public record ProviderUpdate(String model, Double temperature, Integer maxTokens, Integer timeoutSeconds, String endpoint) {}

    public record RouteUpdate(String task, String provider, String model) {}

    public record ConfigUpdate(Boolean aiEnabled, String privacyMode, Boolean fallbackEnabled, Integer maxRetries,
                               ProviderUpdate gemini, ProviderUpdate ollama, List<RouteUpdate> routes,
                               String geminiApiKey, Boolean removeGeminiApiKey) {
        /** Never print the key. */
        @Override
        public String toString() {
            return "ConfigUpdate[mode=" + privacyMode + ", key=" + (geminiApiKey == null ? "unchanged" : "new") + "]";
        }
    }

    public ConfigView save(ConfigUpdate u, Long userId) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Snapshot current = config.current();

        PrivacyMode mode = parseMode(u.privacyMode(), errors);
        int retries = u.maxRetries() == null ? current.maxRetries() : u.maxRetries();
        if (retries < 0 || retries > 3) errors.add("Retries must be between 0 and 3.");

        String key = null;
        boolean keyChange = false;
        if (Boolean.TRUE.equals(u.removeGeminiApiKey())) {
            key = "";
            keyChange = true;
        } else if (u.geminiApiKey() != null && !u.geminiApiKey().isBlank()) {
            key = u.geminiApiKey().trim();
            keyChange = true;
            if (key.length() < 20 || key.length() > 200 || key.chars().anyMatch(Character::isWhitespace)) {
                errors.add("That doesn't look like an API key (20–200 characters, no spaces).");
            }
        }

        LlmSettings row = new LlmSettings();
        row.setPrivacyMode(mode != null ? mode : PrivacyMode.CUSTOM);
        row.setAiEnabled(u.aiEnabled() == null || u.aiEnabled());
        row.setFallbackEnabled(Boolean.TRUE.equals(u.fallbackEnabled()));
        row.setMaxRetries(retries);
        applyProvider(u.gemini(), current.gemini(), row, true, errors);
        applyProvider(u.ollama(), current.ollama(), row, false, errors);
        if (!errors.isEmpty()) throw badRequest(errors);

        // The provider settings as they will be after saving, for the capability checks.
        String effectiveKey = keyChange ? (key.isEmpty() ? (current.keySource() == LlmConfigService.KeySource.ENVIRONMENT
            ? current.gemini().apiKey() : null) : key)
            : current.gemini().apiKey();
        ProviderSettings gemini = new ProviderSettings(LlmProviderId.GEMINI, current.gemini().endpoint(), effectiveKey,
            row.getGeminiModel(), row.getGeminiTemperature(), row.getGeminiMaxTokens(), row.getGeminiTimeoutSeconds());
        ProviderSettings ollama = new ProviderSettings(LlmProviderId.OLLAMA, row.getOllamaBaseUrl(), null,
            row.getOllamaModel(), row.getOllamaTemperature(), row.getOllamaMaxTokens(), row.getOllamaTimeoutSeconds());

        Map<LlmTask, RouteUpdate> requested = new EnumMap<>(LlmTask.class);
        for (RouteUpdate r : u.routes() == null ? List.<RouteUpdate>of() : u.routes()) {
            try {
                requested.put(LlmTask.valueOf(r.task()), r);
            } catch (Exception e) {
                errors.add("Unknown task " + r.task() + ".");
            }
        }

        for (LlmTask t : LlmTask.values()) {
            RouteUpdate r = requested.get(t);
            LlmProviderId provider;
            if (row.getPrivacyMode() == PrivacyMode.CUSTOM) {
                provider = r == null ? current.route(t).provider() : LlmProviderId.parse(r.provider());
            } else {
                provider = row.getPrivacyMode().presetFor(t);
            }
            String model = r != null && provider != null && provider == LlmProviderId.parse(r.provider())
                && r.model() != null && !r.model().isBlank() ? r.model().trim() : null;
            if (model != null && !MODEL_NAME.matcher(model).matches()) {
                errors.add(t.label() + ": the model name has characters a model name can't have.");
                continue;
            }
            if (provider == null) {
                row.getRoutes().put(t, LlmSettings.TaskRouteColumns.off());
                continue;
            }
            ProviderSettings ps = provider == LlmProviderId.GEMINI ? gemini : ollama;
            String effectiveModel = model != null ? model : ps.defaultModel();
            Optional<ModelInfo> info = catalog.find(ps.withModel(effectiveModel), effectiveModel);

            // Local AI and scans: pick an installed vision model rather than send scans out.
            if (info.isPresent() && !info.get().supports(t.requires()) && model == null
                    && row.getPrivacyMode() != PrivacyMode.CUSTOM && provider == LlmProviderId.OLLAMA) {
                Optional<ModelInfo> capable = installed(ollama).stream().filter(m -> m.supports(t.requires())).findFirst();
                if (capable.isPresent()) {
                    effectiveModel = capable.get().name();
                    model = effectiveModel;
                    info = capable;
                } else {
                    row.getRoutes().put(t, LlmSettings.TaskRouteColumns.off());
                    warnings.add(t.label() + " is off: no installed local model can do it (needs "
                        + labels(t.requires()) + "). Install one (e.g. a vision model for scans) and save again.");
                    continue;
                }
            }
            if (info.isPresent() && !info.get().supports(t.requires())) {
                Set<LlmCapability> missing = EnumSet.copyOf(t.requires());
                missing.removeAll(info.get().capabilities());
                errors.add(t.label() + ": " + effectiveModel + " can't do " + labels(missing) + ".");
                continue;
            }
            if (info.isEmpty()) {
                if (provider == LlmProviderId.OLLAMA && t.requires().contains(LlmCapability.VISION)) {
                    errors.add(t.label() + ": couldn't confirm that " + effectiveModel
                        + " reads images (is Ollama running, and the model installed?).");
                    continue;
                }
                warnings.add(t.label() + ": couldn't check " + effectiveModel
                    + " (provider unreachable or model not listed) — each call will check again.");
            }
            row.getRoutes().put(t, new LlmSettings.TaskRouteColumns(provider.name(), model));
        }
        if (!errors.isEmpty()) throw badRequest(errors);

        if (row.isFallbackEnabled() && !row.getPrivacyMode().allowsCloud()) {
            warnings.add("Fallback stays local in Local AI mode: nothing falls back to a cloud provider.");
        }
        Snapshot saved = config.save(row, keyChange ? key : null, userId);
        catalog.clear();
        return view(saved, warnings);
    }

    private void applyProvider(ProviderUpdate u, ProviderSettings current, LlmSettings row, boolean gemini, List<String> errors) {
        String name = gemini ? "Gemini" : "Ollama";
        String model = u != null && u.model() != null && !u.model().isBlank() ? u.model().trim() : current.defaultModel();
        if (!MODEL_NAME.matcher(model).matches()) errors.add(name + ": the model name has characters a model name can't have.");
        double temperature = u != null && u.temperature() != null ? u.temperature() : current.temperature();
        if (temperature < 0 || temperature > 2) errors.add(name + ": temperature must be between 0 and 2.");
        Integer maxTokens = u != null ? u.maxTokens() : current.maxTokens();
        if (maxTokens != null && (maxTokens < 1024 || maxTokens > 65536)) {
            errors.add(name + ": max tokens must be between 1,024 and 65,536 (or empty for the default) — "
                + "extraction needs room for a statement's worth of transactions.");
        }
        int timeout = u != null && u.timeoutSeconds() != null ? u.timeoutSeconds() : current.timeoutSeconds();
        if (timeout < 5 || timeout > 600) errors.add(name + ": timeout must be between 5 and 600 seconds.");
        if (gemini) {
            row.setGeminiModel(model);
            row.setGeminiTemperature(temperature);
            row.setGeminiMaxTokens(maxTokens);
            row.setGeminiTimeoutSeconds(timeout);
        } else {
            String url = u != null && u.endpoint() != null && !u.endpoint().isBlank() ? u.endpoint().trim() : current.endpoint();
            if (!validHttpUrl(url)) errors.add("Ollama: the base URL must be an http(s) address, e.g. http://localhost:11434.");
            row.setOllamaBaseUrl(stripSlash(url));
            row.setOllamaModel(model);
            row.setOllamaTemperature(temperature);
            row.setOllamaMaxTokens(maxTokens);
            row.setOllamaTimeoutSeconds(timeout);
        }
    }

    // ------------------------------------------------------------------ test, models, status

    public record TestRequest(String provider, String model, String endpoint, String apiKey) {
        @Override
        public String toString() { return "TestRequest[" + provider + ", " + model + "]"; }
    }

    public record Step(String label, boolean ok, String detail) {}

    @Value @Builder
    public static class TestResult {
        String provider;
        String model;
        boolean ok;
        List<Step> steps;
        List<String> capabilities;
        boolean capabilitiesKnown;
        List<ModelInfo> models;
        Long latencyMs;
    }

    /**
     * Reachability, model presence and structured output, in that order; stops at the first
     * failure. Unsaved values from the form (a new key, another address) can be tried before saving.
     */
    public TestResult test(TestRequest req) {
        LlmProviderId id = LlmProviderId.parse(req.provider());
        if (id == null) throw badRequest(List.of("Choose a provider to test."));
        Snapshot s = config.current();
        ProviderSettings base = s.provider(id);
        String model = req.model() != null && !req.model().isBlank() ? req.model().trim() : base.defaultModel();
        if (!MODEL_NAME.matcher(model).matches()) throw badRequest(List.of("That model name isn't valid."));
        String endpoint = base.endpoint();
        if (id == LlmProviderId.OLLAMA && req.endpoint() != null && !req.endpoint().isBlank()) {
            if (!validHttpUrl(req.endpoint().trim())) throw badRequest(List.of("The base URL must be an http(s) address."));
            endpoint = stripSlash(req.endpoint().trim());
        }
        String key = id == LlmProviderId.GEMINI && req.apiKey() != null && !req.apiKey().isBlank() ? req.apiKey().trim() : base.apiKey();
        ProviderSettings settings = new ProviderSettings(id, endpoint, key, model, base.temperature(), base.maxTokens(), base.timeoutSeconds());

        List<Step> steps = new ArrayList<>();
        String reach = id == LlmProviderId.GEMINI ? "API connection" : "Ollama reachable";
        if (id.cloud() && !s.privacyMode().allowsCloud()) {
            steps.add(new Step(reach, false, "Not tested: the privacy mode is Local AI, which never contacts a cloud provider."));
            return result(id, model, steps, null, null);
        }
        if (id == LlmProviderId.GEMINI && (key == null || key.isBlank())) {
            steps.add(new Step(reach, false, "No API key: enter one and test again."));
            return result(id, model, steps, null, null);
        }

        List<ModelInfo> models;
        try {
            models = catalog.refresh(settings);
            steps.add(new Step(reach, true, id == LlmProviderId.GEMINI ? "API key accepted" : "Ollama is running at " + endpoint));
        } catch (LlmUnavailableException e) {
            steps.add(new Step(reach, false, reachFailure(id, e.getCategory())));
            return result(id, model, steps, null, null);
        }

        Optional<ModelInfo> info = models.stream().filter(m -> LlmProvider.sameModel(m.name(), model)).findFirst();
        if (info.isEmpty()) {
            steps.add(new Step("Model available", false, id == LlmProviderId.OLLAMA
                ? model + " is not installed (run: ollama pull " + model + ")"
                : model + " is not offered to this API key"));
            return result(id, model, steps, null, models);
        }
        steps.add(new Step("Model available", true, info.get().name()));

        long started = System.currentTimeMillis();
        try {
            LlmCompletion c = catalog.provider(id).generate(settings, new LlmRequest(
                PromptLibrary.CONNECTION_CHECK.system(), "Return the JSON object now.", info.get().name(), true, 0.0,
                null, settings.timeoutSeconds(), List.of()));
            Optional<JsonNode> parsed = json.parse(c.getText());
            boolean ok = parsed.map(n -> n.path("ok").asBoolean(false)).orElse(false);
            steps.add(new Step("Structured output supported", ok, ok ? "Returned valid JSON"
                : "The model did not return the requested JSON — structured extraction would fail on it"));
        } catch (LlmUnavailableException e) {
            steps.add(new Step("Structured output supported", false, e.getCategory().message()));
        }
        return result(id, model, steps, info.get(), models, System.currentTimeMillis() - started);
    }

    private static String reachFailure(LlmProviderId id, LlmErrorCategory c) {
        if (id == LlmProviderId.OLLAMA && c == LlmErrorCategory.NOT_RUNNING) return "Ollama is not running at the configured address";
        if (id == LlmProviderId.GEMINI && c == LlmErrorCategory.AUTH_INVALID) return "Gemini API key invalid";
        if (c == LlmErrorCategory.RATE_LIMITED) return "API quota or rate limit reached — try again later";
        return c.message();
    }

    private TestResult result(LlmProviderId id, String model, List<Step> steps, ModelInfo info, List<ModelInfo> models) {
        return result(id, model, steps, info, models, null);
    }

    private TestResult result(LlmProviderId id, String model, List<Step> steps, ModelInfo info, List<ModelInfo> models, Long latency) {
        return TestResult.builder()
            .provider(id.name()).model(model)
            .ok(!steps.isEmpty() && steps.stream().allMatch(Step::ok) && steps.size() == 3)
            .steps(steps)
            .capabilities(info == null ? List.of() : info.capabilities().stream().map(Enum::name).toList())
            .capabilitiesKnown(info != null && info.capabilitiesKnown())
            .models(models == null ? List.of() : models)
            .latencyMs(latency)
            .build();
    }

    /** Models on offer, refreshed from the provider. */
    public List<ModelInfo> models(String provider) {
        LlmProviderId id = LlmProviderId.parse(provider);
        if (id == null) throw badRequest(List.of("Unknown provider."));
        Snapshot s = config.current();
        if (id.cloud() && !s.privacyMode().allowsCloud()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Local AI mode never contacts a cloud provider.");
        }
        try {
            return catalog.refresh(s.provider(id));
        } catch (LlmUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, reachFailure(id, e.getCategory()));
        }
    }

    private List<ModelInfo> installed(ProviderSettings ollama) {
        try {
            return catalog.refresh(ollama);
        } catch (LlmUnavailableException e) {
            return List.of();
        }
    }

    @Value @Builder
    public static class ProviderStatus {
        String provider;
        boolean configured;
        /** true/false when checked; null when not checked (a cloud provider in Local AI mode). */
        Boolean connected;
        String detail;
    }

    @Value @Builder
    public static class Health {
        List<ProviderStatus> providers;
        LlmUsageService.Summary usage;
    }

    /** Connection status of each provider plus today's processing figures. */
    public Health health() {
        Snapshot s = config.current();
        List<ProviderStatus> statuses = new ArrayList<>();
        for (LlmProviderId id : LlmProviderId.values()) {
            ProviderSettings p = s.provider(id);
            ProviderStatus.ProviderStatusBuilder b = ProviderStatus.builder().provider(id.name()).configured(p.configured());
            if (!s.permits(id)) {
                statuses.add(b.connected(null).detail("Not used: Local AI mode").build());
            } else if (!p.configured()) {
                statuses.add(b.connected(false).detail(id == LlmProviderId.GEMINI ? "No API key" : "Not configured").build());
            } else {
                try {
                    List<ModelInfo> models = catalog.refresh(p);
                    boolean hasModel = models.stream().anyMatch(m -> LlmProvider.sameModel(m.name(), p.defaultModel()));
                    statuses.add(b.connected(true).detail(hasModel ? "Connected" : "Connected, but " + p.defaultModel() + " is not available").build());
                } catch (LlmUnavailableException e) {
                    statuses.add(b.connected(false).detail(reachFailure(id, e.getCategory())).build());
                }
            }
        }
        return Health.builder().providers(statuses).usage(usage.today()).build();
    }

    // ------------------------------------------------------------------ helpers

    private static PrivacyMode parseMode(String s, List<String> errors) {
        try {
            return PrivacyMode.valueOf(s);
        } catch (Exception e) {
            errors.add("Choose a privacy mode.");
            return null;
        }
    }

    private static String labels(Set<LlmCapability> caps) {
        return String.join(", ", caps.stream().map(LlmCapability::label).toList());
    }

    static boolean validHttpUrl(String url) {
        try {
            URI u = URI.create(url);
            return ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null
                && u.getUserInfo() == null && u.getQuery() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static ResponseStatusException badRequest(List<String> errors) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", errors));
    }
}
