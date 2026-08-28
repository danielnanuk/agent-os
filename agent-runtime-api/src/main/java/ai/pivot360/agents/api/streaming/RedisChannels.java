package ai.pivot360.agents.api.streaming;

import java.util.UUID;

/** One pub/sub channel per run (not one firehose channel) so subscribers only receive traffic for the run they're watching. */
public final class RedisChannels {

    private RedisChannels() {
    }

    public static String forRun(String tenantId, UUID agentRunId) {
        return "agent-events:" + tenantId + ":" + agentRunId;
    }
}
