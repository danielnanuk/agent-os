package ai.pivot360.agents.temporal.workflow;

import ai.pivot360.agents.common.workflow.AgentRunInput;
import ai.pivot360.agents.common.workflow.AgentRunProgress;
import ai.pivot360.agents.common.workflow.AgentRunResult;
import ai.pivot360.agents.common.workflow.ApprovalDecision;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface AgentRunWorkflow {

    @WorkflowMethod
    AgentRunResult run(AgentRunInput input);

    /** Resumes a run blocked in WAITING_FOR_APPROVAL. Not exercised by the MVP demo agent. */
    @SignalMethod
    void submitApproval(ApprovalDecision decision);

    @SignalMethod
    void cancel();

    @QueryMethod
    AgentRunProgress getProgress();
}
