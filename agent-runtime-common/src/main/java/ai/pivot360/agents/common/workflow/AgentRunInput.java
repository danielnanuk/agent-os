package ai.pivot360.agents.common.workflow;

import ai.pivot360.agents.common.activity.ChatMessageDto;
import ai.pivot360.agents.common.agent.LlmModelConfig;

import java.util.List;
import java.util.UUID;

/**
 * Snapshot of everything the {@code AgentRunWorkflow} needs to execute a run.
 * Resolved once by {@code AgentRunService} before starting the workflow so the
 * workflow itself never needs to read the database just to know its own config.
 */
public record AgentRunInput(
        String tenantId,
        UUID agentRunId,
        UUID agentDefinitionId,
        String agentKey,
        String taskInput,
        LlmModelConfig llmModelConfig,
        String systemPrompt,
        int maxIterations,
        List<String> toolNames,
        /** Prior turns of the same conversation, seeded before this turn's user message. Empty for a new conversation. */
        List<ChatMessageDto> priorHistory
) {
}
