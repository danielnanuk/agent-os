package ai.pivot360.agents.temporal;

/**
 * MVP uses a single shared task queue. Future per-agent-type isolation would follow
 * the pattern {@code "agent-runtime-" + agentKey} and register a dedicated worker
 * per queue.
 */
public final class TemporalQueues {

    public static final String AGENT_RUNTIME_DEFAULT = "agent-runtime-default";

    private TemporalQueues() {
    }
}
