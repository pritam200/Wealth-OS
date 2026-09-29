package com.marketai.ai.llm;

/**
 * Everything one provider needs for a call, resolved by {@code LlmConfigService} from the saved
 * configuration (or the environment defaults). {@link #toString()} leaves the key out, so a
 * settings object logged by mistake does not leak it.
 *
 * @param endpoint     base URL (Gemini's API root, or the Ollama server)
 * @param apiKey       decrypted key, or null (Ollama needs none)
 * @param defaultModel model used when a task names none
 * @param temperature  for prose tasks; structured tasks always run at 0
 * @param maxTokens    output cap, or null for the provider's own default
 */
public record ProviderSettings(LlmProviderId id, String endpoint, String apiKey, String defaultModel,
                               double temperature, Integer maxTokens, int timeoutSeconds) {

    /** Enough to attempt a call: a key for a cloud provider, an address and a model for any. */
    public boolean configured() {
        boolean hasModel = defaultModel != null && !defaultModel.isBlank();
        boolean hasEndpoint = endpoint != null && !endpoint.isBlank();
        return hasModel && hasEndpoint && (!id.cloud() || (apiKey != null && !apiKey.isBlank()));
    }

    public ProviderSettings withModel(String model) {
        return new ProviderSettings(id, endpoint, apiKey, model, temperature, maxTokens, timeoutSeconds);
    }

    @Override
    public String toString() {
        return "ProviderSettings[" + id + ", endpoint=" + endpoint + ", model=" + defaultModel
            + ", key=" + (apiKey == null || apiKey.isBlank() ? "none" : "set") + "]";
    }
}
