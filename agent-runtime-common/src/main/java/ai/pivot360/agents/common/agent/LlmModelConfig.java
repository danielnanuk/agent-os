package ai.pivot360.agents.common.agent;

/**
 * Immutable snapshot of an {@code llm_models} row -- everything needed to build (and
 * cache) a {@code ChatModel} client for one specific provider/endpoint/key/model
 * combination. Used as a cache key by {@code ChatModelResolver}, so equal values
 * (e.g. after re-reading the same row) reuse the same underlying client.
 */
public record LlmModelConfig(
        LlmProvider provider,
        String baseUrl,
        String apiKeyEnv,
        String modelName
) {
}
