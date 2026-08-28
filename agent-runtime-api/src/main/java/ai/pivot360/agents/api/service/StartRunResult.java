package ai.pivot360.agents.api.service;

import java.util.UUID;

/** conversationId is echoed back so a caller (e.g. the voice worker) can reuse it on the next turn. */
public record StartRunResult(UUID agentRunId, UUID conversationId) {
}
