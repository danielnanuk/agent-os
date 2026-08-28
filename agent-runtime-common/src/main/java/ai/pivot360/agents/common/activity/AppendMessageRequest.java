package ai.pivot360.agents.common.activity;

import ai.pivot360.agents.common.agent.MessageRole;

import java.util.UUID;

public record AppendMessageRequest(
        String tenantId,
        UUID agentRunId,
        MessageRole role,
        String content,
        String toolName,
        String toolCallId,
        int sequenceNumber
) {
}
