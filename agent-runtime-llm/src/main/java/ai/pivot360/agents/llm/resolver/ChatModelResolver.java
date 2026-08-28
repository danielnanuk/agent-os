package ai.pivot360.agents.llm.resolver;

import ai.pivot360.agents.common.agent.LlmModelConfig;
import ai.pivot360.agents.common.exception.AgentRuntimeException;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.client.AnthropicClientAsyncImpl;
import com.anthropic.client.AnthropicClientImpl;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.http.okhttp.SpringAiAnthropicHttpClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds (and caches) a {@link ChatModel} client for a given {@link LlmModelConfig} --
 * unlike a fixed per-provider Spring bean, this lets different {@code llm_models} rows
 * point at different base URLs/keys for the *same* provider type at the same time (e.g.
 * one agent on DeepSeek, another on GLM, both "OPENAI" provider) with zero app restart:
 * add/update a row, repoint an agent's {@code llm_model_id}, done.
 *
 * <p>Cached by the {@link LlmModelConfig} value itself (a record, so structurally equal
 * configs share a client) rather than by row id, so re-reading an updated row naturally
 * builds a fresh client instead of serving a stale cached one.
 */
@Component
public class ChatModelResolver {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    private final ConcurrentHashMap<LlmModelConfig, ChatModel> cache = new ConcurrentHashMap<>();

    public ChatModel resolve(LlmModelConfig config) {
        return cache.computeIfAbsent(config, this::build);
    }

    private ChatModel build(LlmModelConfig config) {
        String apiKey = resolveApiKey(config.apiKeyEnv());
        return switch (config.provider()) {
            case OPENAI -> buildOpenAi(config, apiKey);
            case ANTHROPIC -> buildAnthropic(config, apiKey);
            case OLLAMA -> buildOllama(config);
        };
    }

    private ChatModel buildOpenAi(LlmModelConfig config, String apiKey) {
        com.openai.core.ClientOptions.Builder options = com.openai.core.ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder().build());
        if (hasText(config.baseUrl())) {
            options.baseUrl(config.baseUrl());
        }
        if (hasText(apiKey)) {
            options.apiKey(apiKey);
        }
        // OpenAiChatModel.Builder auto-derives an async client from ambient env vars
        // (e.g. OPENAI_API_KEY) if one isn't supplied -- that fallback doesn't know about
        // our custom apiKeyEnv, so it fails "at least one credential source must be
        // specified" even though the sync client above is fully configured. Building both
        // clients from the exact same ClientOptions sidesteps that fallback entirely.
        com.openai.core.ClientOptions built = options.build();
        OpenAIClient client = new OpenAIClientImpl(built);
        OpenAIClientAsync asyncClient = new OpenAIClientAsyncImpl(built);
        return OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(asyncClient).build();
    }

    private ChatModel buildAnthropic(LlmModelConfig config, String apiKey) {
        com.anthropic.core.ClientOptions.Builder options = com.anthropic.core.ClientOptions.builder()
                .httpClient(SpringAiAnthropicHttpClient.builder().build())
                .putHeader("anthropic-version", ANTHROPIC_VERSION);
        if (hasText(config.baseUrl())) {
            options.baseUrl(config.baseUrl());
        }
        if (hasText(apiKey)) {
            options.putHeader("x-api-key", apiKey);
        }
        // Same rationale as buildOpenAi: supply both clients explicitly to avoid an
        // ambient-env-based async-client fallback that doesn't know our custom apiKeyEnv.
        com.anthropic.core.ClientOptions built = options.build();
        AnthropicClient client = new AnthropicClientImpl(built);
        AnthropicClientAsync asyncClient = new AnthropicClientAsyncImpl(built);
        return AnthropicChatModel.builder().anthropicClient(client).anthropicClientAsync(asyncClient).build();
    }

    private ChatModel buildOllama(LlmModelConfig config) {
        OllamaApi api = OllamaApi.builder()
                .baseUrl(hasText(config.baseUrl()) ? config.baseUrl() : DEFAULT_OLLAMA_BASE_URL)
                .build();
        return OllamaChatModel.builder().ollamaApi(api).build();
    }

    private String resolveApiKey(String apiKeyEnv) {
        if (!hasText(apiKeyEnv)) {
            return null;
        }
        String value = System.getenv(apiKeyEnv);
        if (!hasText(value)) {
            throw new AgentRuntimeException("Environment variable " + apiKeyEnv + " is not set");
        }
        return value;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
