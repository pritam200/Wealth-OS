package com.marketai.ai.llm;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which models each provider offers and what they can do, cached for a few minutes so the
 * capability check before each call costs no network round trip.
 */
@Component
public class LlmModelCatalog {

    private static final long TTL_MS = 5 * 60_000L;

    private final Map<LlmProviderId, LlmProvider> providers;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(List<ModelInfo> models, long at) {}

    public LlmModelCatalog(List<LlmProvider> providers) {
        this.providers = new java.util.EnumMap<>(LlmProviderId.class);
        for (LlmProvider p : providers) this.providers.put(p.id(), p);
    }

    public LlmProvider provider(LlmProviderId id) {
        LlmProvider p = providers.get(id);
        if (p == null) throw new IllegalStateException("No provider implementation for " + id);
        return p;
    }

    /** Fresh list from the provider (and refreshes the cache). Throws LlmUnavailableException. */
    public List<ModelInfo> refresh(ProviderSettings settings) {
        List<ModelInfo> models = provider(settings.id()).listModels(settings);
        cache.put(key(settings), new Cached(models, System.currentTimeMillis()));
        return models;
    }

    /** The model's entry, or empty when it could not be determined (provider unreachable, no key). */
    public Optional<ModelInfo> find(ProviderSettings settings, String model) {
        Cached c = cache.get(key(settings));
        List<ModelInfo> models;
        if (c != null && System.currentTimeMillis() - c.at() < TTL_MS) {
            models = c.models();
        } else {
            try {
                models = refresh(settings);
            } catch (LlmUnavailableException e) {
                return Optional.empty();
            }
        }
        return models.stream().filter(m -> LlmProvider.sameModel(m.name(), model)).findFirst();
    }

    public void clear() { cache.clear(); }

    /** Endpoint and whether a key is set — never the key itself. */
    private static String key(ProviderSettings s) {
        return s.id() + "|" + s.endpoint() + "|" + (s.apiKey() == null ? 0 : s.apiKey().hashCode());
    }
}
