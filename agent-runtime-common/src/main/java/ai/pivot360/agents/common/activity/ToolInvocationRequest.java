package ai.pivot360.agents.common.activity;

import java.util.UUID;

public record ToolInvocationRequest(
        String tenantId,
        UUID agentRunId,
        ToolCallDto toolCall
) {
}
