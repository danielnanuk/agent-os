package ai.pivot360.agents.temporal.activity.impl;

import ai.pivot360.agents.common.activity.ChatMessageDto;
import ai.pivot360.agents.common.activity.LlmCallRequest;
import ai.pivot360.agents.common.activity.LlmResponse;
import ai.pivot360.agents.common.activity.ToolCallDto;
import ai.pivot360.agents.common.agent.LlmModelConfig;
import ai.pivot360.agents.common.event.ProgressEvent;
import ai.pivot360.agents.common.event.ProgressEventPublisher;
import ai.pivot360.agents.common.event.ProgressEventType;
import ai.pivot360.agents.llm.resolver.ChatModelResolver;
import ai.pivot360.agents.temporal.TemporalQueues;
import ai.pivot360.agents.temporal.activity.LlmActivities;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ActivityImpl(taskQueues = TemporalQueues.AGENT_RUNTIME_DEFAULT)
public class LlmActivitiesImpl implements LlmActivities {

    private static final Logger log = LoggerFactory.getLogger(LlmActivitiesImpl.class);

    private final ChatModelResolver chatModelResolver;
    private final CompositeToolResolver toolResolver;
    private final ProgressEventPublisher progressEventPublisher;

    public LlmActivitiesImpl(ChatModelResolver chatModelResolver, CompositeToolResolver toolResolver,
                              ProgressEventPublisher progressEventPublisher) {
        this.chatModelResolver = chatModelResolver;
        this.toolResolver = toolResolver;
        this.progressEventPublisher = progressEventPublisher;
    }

    @Override
    public LlmResponse callChatModel(LlmCallRequest request) {
        ChatModel chatModel;
        try {
            chatModel = chatModelResolver.resolve(request.llmModelConfig());
        } catch (RuntimeException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "LLM_AUTH_ERROR");
        }

        List<Message> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        for (ChatMessageDto m : request.messages()) {
            messages.add(toSpringAiMessage(m));
        }

        List<ToolCallback> toolCallbacks = request.availableToolNames() == null || request.availableToolNames().isEmpty()
                ? List.of()
                : toolResolver.resolve(request.availableToolNames());

        ChatOptions options = buildOptions(request.llmModelConfig(), toolCallbacks);

        // Tool-call argument fragments only make sense once fully reassembled, so we stream
        // raw chunks (for live text deltas) through MessageAggregator, which hands back the
        // one fully-merged ChatResponse (complete tool calls included) once the stream ends.
        AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
        AtomicLong deltaSeq = new AtomicLong();
        new MessageAggregator()
                .aggregate(chatModel.stream(new Prompt(messages, options)), aggregated::set)
                .doOnNext(chunk -> {
                    String delta = chunk.getResult().getOutput().getText();
                    if (delta != null && !delta.isEmpty()) {
                        publishDelta(request, deltaSeq.getAndIncrement(), delta);
                    }
                })
                .blockLast();

        AssistantMessage output = aggregated.get().getResult().getOutput();

        List<ToolCallDto> toolCalls = output.hasToolCalls()
                ? output.getToolCalls().stream()
                        .map(tc -> new ToolCallDto(tc.id(), tc.name(), tc.arguments()))
                        .toList()
                : List.of();

        return new LlmResponse(output.getText(), toolCalls);
    }

    /** Best-effort live UX ornament -- the authoritative final content is the Activity's return value. */
    private void publishDelta(LlmCallRequest request, long seq, String delta) {
        try {
            progressEventPublisher.publish(ProgressEvent.of(
                    request.tenantId(), request.agentRunId(), seq, ProgressEventType.LLM_DELTA, delta));
        } catch (RuntimeException e) {
            log.debug("Failed to publish LLM delta for run {}: {}", request.agentRunId(), e.getMessage());
        }
    }

    /**
     * Each concrete ChatModel (OpenAiChatModel, AnthropicChatModel, OllamaChatModel)
     * casts {@code Prompt.getOptions()} to its own provider-specific Options type
     * internally -- the generic {@code ToolCallingChatOptions.builder()} produces a
     * {@code DefaultToolCallingChatOptions} that fails that cast, so options must be
     * built via the matching provider's own builder.
     */
    private ChatOptions buildOptions(LlmModelConfig config, List<ToolCallback> toolCallbacks) {
        String model = config.modelName();
        return switch (config.provider()) {
            case OPENAI -> OpenAiChatOptions.builder().model(model).toolCallbacks(toolCallbacks).build();
            case ANTHROPIC -> AnthropicChatOptions.builder().model(model).toolCallbacks(toolCallbacks).build();
            case OLLAMA -> OllamaChatOptions.builder().model(model).toolCallbacks(toolCallbacks).build();
        };
    }

    private Message toSpringAiMessage(ChatMessageDto m) {
        return switch (m.role()) {
            case USER -> new UserMessage(m.content());
            case SYSTEM -> new SystemMessage(m.content());
            case ASSISTANT -> AssistantMessage.builder()
                    .content(m.content())
                    .toolCalls(toSpringAiToolCalls(m.toolCalls()))
                    .build();
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(m.toolCallId(), m.toolName(), m.content())))
                    .build();
        };
    }

    private List<AssistantMessage.ToolCall> toSpringAiToolCalls(List<ToolCallDto> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        return toolCalls.stream()
                .map(tc -> new AssistantMessage.ToolCall(tc.id(), "function", tc.toolName(), tc.argumentsJson()))
                .toList();
    }
}
