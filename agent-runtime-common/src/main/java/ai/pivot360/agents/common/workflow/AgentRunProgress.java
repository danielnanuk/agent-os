package ai.pivot360.agents.common.workflow;

import ai.pivot360.agents.common.agent.AgentRunStatus;

/**
 * Result of a Temporal {@code @QueryMethod} — a synchronous, side-effect-free
 * snapshot of workflow state, independent of the Redis streaming path.
 */
public record AgentRunProgress(
        AgentRunStatus status,
        int currentIteration
) {
}
