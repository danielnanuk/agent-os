package ai.pivot360.agents.common.workflow;

import ai.pivot360.agents.common.agent.AgentRunStatus;

import java.util.UUID;

public record AgentRunResult(
        UUID agentRunId,
        AgentRunStatus status,
        String finalAnswer,
        String errorMessage
) {
}
