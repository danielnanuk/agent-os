package ai.pivot360.agents.common.activity;

import ai.pivot360.agents.common.agent.LlmModelConfig;

import java.util.List;
import java.util.UUID;

public record LlmCallRequest(
        String tenantId,
        UUID agentRunId,
        LlmModelConfig llmModelConfig,
        String systemPrompt,
        List<ChatMessageDto> messages,
        List<String> availableToolNames
) {
}
