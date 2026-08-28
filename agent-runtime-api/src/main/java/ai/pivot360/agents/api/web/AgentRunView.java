package ai.pivot360.agents.api.web;

import ai.pivot360.agents.common.agent.AgentRunStatus;

import java.util.UUID;

public record AgentRunView(
        UUID runId,
        AgentRunStatus status,
        String result,
        String errorMessage
) {
}
