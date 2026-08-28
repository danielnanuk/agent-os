package ai.pivot360.agents.temporal.workflow;

import ai.pivot360.agents.common.activity.AppendMessageRequest;
import ai.pivot360.agents.common.activity.ChatMessageDto;
import ai.pivot360.agents.common.activity.LlmCallRequest;
import ai.pivot360.agents.common.activity.LlmResponse;
import ai.pivot360.agents.common.activity.SaveRunResultRequest;
import ai.pivot360.agents.common.activity.ToolCallDto;
import ai.pivot360.agents.common.activity.ToolInvocationRequest;
import ai.pivot360.agents.common.activity.ToolResult;
import ai.pivot360.agents.common.activity.UpdateRunStatusRequest;
import ai.pivot360.agents.common.agent.AgentRunStatus;
import ai.pivot360.agents.common.event.ProgressEvent;
import ai.pivot360.agents.common.event.ProgressEventType;
import ai.pivot360.agents.common.workflow.AgentRunInput;
import ai.pivot360.agents.common.workflow.AgentRunProgress;
import ai.pivot360.agents.common.workflow.AgentRunResult;
import ai.pivot360.agents.common.workflow.ApprovalDecision;
import ai.pivot360.agents.temporal.TemporalQueues;
import ai.pivot360.agents.temporal.activity.LlmActivities;
import ai.pivot360.agents.temporal.activity.PersistenceActivities;
import ai.pivot360.agents.temporal.activity.StreamingActivities;
import ai.pivot360.agents.temporal.activity.ToolActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@WorkflowImpl(taskQueues = TemporalQueues.AGENT_RUNTIME_DEFAULT)
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    private final LlmActivities llmActivities = Workflow.newActivityStub(
            LlmActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    private final ToolActivities toolActivities = Workflow.newActivityStub(
            ToolActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    private final PersistenceActivities persistenceActivities = Workflow.newActivityStub(
            PersistenceActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
                    .build());

    private final StreamingActivities streamingActivities = Workflow.newActivityStub(
            StreamingActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    private AgentRunStatus status = AgentRunStatus.PENDING;
    private int currentIteration = 0;
    private long eventSequence = 0;
    private boolean cancelRequested = false;
    private ApprovalDecision lastApprovalDecision;

    @Override
    public AgentRunResult run(AgentRunInput input) {
        status = AgentRunStatus.RUNNING;
        persistenceActivities.updateRunStatus(new UpdateRunStatusRequest(
                input.tenantId(), input.agentRunId(), AgentRunStatus.RUNNING, null));

        List<ChatMessageDto> history = new ArrayList<>(input.priorHistory());
        int sequenceNumber = 0;

        ChatMessageDto userMessage = ChatMessageDto.user(input.taskInput());
        history.add(userMessage);
        persistenceActivities.appendMessage(toAppendRequest(input, userMessage, sequenceNumber++));
        publish(input, ProgressEventType.RUN_STARTED, input.taskInput());

        String finalAnswer = null;
        try {
            while (currentIteration < input.maxIterations()) {
                if (cancelRequested) {
                    status = AgentRunStatus.CANCELLED;
                    persistenceActivities.updateRunStatus(new UpdateRunStatusRequest(
                            input.tenantId(), input.agentRunId(), AgentRunStatus.CANCELLED, null));
                    return new AgentRunResult(input.agentRunId(), AgentRunStatus.CANCELLED, null, "Cancelled");
                }
                currentIteration++;

                LlmResponse llmResponse = llmActivities.callChatModel(new LlmCallRequest(
                        input.tenantId(), input.agentRunId(), input.llmModelConfig(),
                        input.systemPrompt(), history, input.toolNames()));

                ChatMessageDto assistantMessage = ChatMessageDto.assistant(
                        llmResponse.assistantContent(), llmResponse.toolCalls());
                history.add(assistantMessage);
                persistenceActivities.appendMessage(toAppendRequest(input, assistantMessage, sequenceNumber++));
                publish(input, ProgressEventType.LLM_THOUGHT, llmResponse.assistantContent());

                if (!llmResponse.hasToolCalls()) {
                    finalAnswer = llmResponse.assistantContent();
                    break;
                }

                for (ToolCallDto toolCall : llmResponse.toolCalls()) {
                    publish(input, ProgressEventType.TOOL_CALL_STARTED, toolCall.toolName());

                    ToolResult toolResult = toolActivities.invokeTool(
                            new ToolInvocationRequest(input.tenantId(), input.agentRunId(), toolCall));
                    // A failed tool call (e.g. a search API error) still needs non-null text
                    // content -- surface the error to the model instead of a null message body,
                    // so it can react (retry, apologize, answer without it) rather than crash.
                    String toolContent = toolResult.success()
                            ? toolResult.resultContent()
                            : "Error: " + toolResult.errorMessage();

                    ChatMessageDto toolMessage = ChatMessageDto.tool(
                            toolResult.toolName(), toolResult.toolCallId(), toolContent);
                    history.add(toolMessage);
                    persistenceActivities.appendMessage(toAppendRequest(input, toolMessage, sequenceNumber++));
                    publish(input, ProgressEventType.TOOL_CALL_COMPLETED, toolContent);
                }
            }
        } catch (RuntimeException e) {
            status = AgentRunStatus.FAILED;
            persistenceActivities.updateRunStatus(new UpdateRunStatusRequest(
                    input.tenantId(), input.agentRunId(), AgentRunStatus.FAILED, e.getMessage()));
            publish(input, ProgressEventType.RUN_FAILED, e.getMessage());
            throw ApplicationFailure.newFailureWithCause("Agent run failed", "AGENT_RUN_FAILED", e);
        }

        status = AgentRunStatus.COMPLETED;
        persistenceActivities.saveRunResult(new SaveRunResultRequest(input.tenantId(), input.agentRunId(), finalAnswer));
        persistenceActivities.updateRunStatus(new UpdateRunStatusRequest(
                input.tenantId(), input.agentRunId(), AgentRunStatus.COMPLETED, null));
        publish(input, ProgressEventType.RUN_COMPLETED, finalAnswer);

        return new AgentRunResult(input.agentRunId(), AgentRunStatus.COMPLETED, finalAnswer, null);
    }

    @Override
    public void submitApproval(ApprovalDecision decision) {
        this.lastApprovalDecision = decision;
    }

    @Override
    public void cancel() {
        this.cancelRequested = true;
    }

    @Override
    public AgentRunProgress getProgress() {
        return new AgentRunProgress(status, currentIteration);
    }

    /**
     * Kept for the human-in-the-loop capability the architecture is meant to prove out;
     * not called from {@link #run} because the MVP demo agent never requires approval.
     * A future agent type that does would call this after setting status to
     * WAITING_FOR_APPROVAL and before continuing the loop.
     */
    private void awaitApproval() {
        lastApprovalDecision = null;
        Workflow.await(() -> lastApprovalDecision != null);
    }

    private AppendMessageRequest toAppendRequest(AgentRunInput input, ChatMessageDto message, int sequenceNumber) {
        return new AppendMessageRequest(input.tenantId(), input.agentRunId(), message.role(), message.content(),
                message.toolName(), message.toolCallId(), sequenceNumber);
    }

    private void publish(AgentRunInput input, ProgressEventType type, String payload) {
        streamingActivities.publishProgressEvent(ProgressEvent.of(
                input.tenantId(), input.agentRunId(), eventSequence++, type, payload));
    }
}
