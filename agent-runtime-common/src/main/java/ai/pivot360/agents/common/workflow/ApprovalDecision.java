package ai.pivot360.agents.common.workflow;

/**
 * Payload of the {@code submitApproval} signal used to resume a workflow that is
 * blocked waiting for human-in-the-loop approval.
 */
public record ApprovalDecision(
        boolean approved,
        String reason,
        String approvedBy
) {
}
