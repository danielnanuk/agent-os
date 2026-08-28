package ai.pivot360.agents.common.activity;

import ai.pivot360.agents.common.agent.AgentRunStatus;

import java.util.UUID;

public record UpdateRunStatusRequest(
        String tenantId,
        UUID agentRunId,
        AgentRunStatus status,
        String errorMessage
) {
}
