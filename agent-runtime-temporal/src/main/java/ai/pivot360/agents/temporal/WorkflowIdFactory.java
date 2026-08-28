package ai.pivot360.agents.temporal;

import java.util.UUID;

/**
 * Builds the Workflow ID with the tenant id as a prefix, so the tenant is visible
 * directly in the Temporal Web UI / tctl listings without relying solely on the
 * TenantId search attribute.
 */
public final class WorkflowIdFactory {

    private WorkflowIdFactory() {
    }

    public static String forAgentRun(String tenantId, UUID agentRunId) {
        return "tenant-" + tenantId + "-agentrun-" + agentRunId;
    }
}
