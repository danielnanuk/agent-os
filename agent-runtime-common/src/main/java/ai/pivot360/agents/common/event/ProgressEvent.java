package ai.pivot360.agents.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A single step-granular progress update for one agent run, published by a Temporal
 * Activity and relayed to any SSE subscriber (possibly on a different JVM instance)
 * via the Redis pub/sub bridge.
 */
public record ProgressEvent(
        String tenantId,
        UUID agentRunId,
        long sequenceNumber,
        ProgressEventType eventType,
        String payload,
        Instant timestamp
) {
    public static ProgressEvent of(String tenantId, UUID agentRunId, long sequenceNumber,
                                    ProgressEventType eventType, String payload) {
        return new ProgressEvent(tenantId, agentRunId, sequenceNumber, eventType, payload, Instant.now());
    }
}
