package ai.pivot360.agents.common.activity;

import java.util.UUID;

public record SaveRunResultRequest(
        String tenantId,
        UUID agentRunId,
        String resultPayload
) {
}
