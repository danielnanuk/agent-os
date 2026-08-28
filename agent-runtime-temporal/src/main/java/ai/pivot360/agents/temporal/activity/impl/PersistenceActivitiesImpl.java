package ai.pivot360.agents.temporal.activity.impl;

import ai.pivot360.agents.common.activity.AppendMessageRequest;
import ai.pivot360.agents.common.activity.SaveRunResultRequest;
import ai.pivot360.agents.common.activity.UpdateRunStatusRequest;
import ai.pivot360.agents.common.exception.NotFoundException;
import ai.pivot360.agents.persistence.entity.AgentRun;
import ai.pivot360.agents.persistence.entity.ConversationMessage;
import ai.pivot360.agents.persistence.repository.AgentRunRepository;
import ai.pivot360.agents.persistence.repository.ConversationMessageRepository;
import ai.pivot360.agents.temporal.TemporalQueues;
import ai.pivot360.agents.temporal.activity.PersistenceActivities;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ActivityImpl(taskQueues = TemporalQueues.AGENT_RUNTIME_DEFAULT)
public class PersistenceActivitiesImpl implements PersistenceActivities {

    private final AgentRunRepository agentRunRepository;
    private final ConversationMessageRepository conversationMessageRepository;
    private final ObjectMapper objectMapper;

    public PersistenceActivitiesImpl(AgentRunRepository agentRunRepository,
                                      ConversationMessageRepository conversationMessageRepository,
                                      ObjectMapper objectMapper) {
        this.agentRunRepository = agentRunRepository;
        this.conversationMessageRepository = conversationMessageRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void appendMessage(AppendMessageRequest request) {
        conversationMessageRepository.save(new ConversationMessage(
                request.tenantId(), request.agentRunId(), request.role(), request.content(),
                request.toolName(), request.toolCallId(), request.sequenceNumber()));
    }

    @Override
    @Transactional
    public void updateRunStatus(UpdateRunStatusRequest request) {
        AgentRun run = findRun(request.tenantId(), request.agentRunId());
        run.setStatus(request.status());
        if (request.errorMessage() != null) {
            run.setErrorMessage(request.errorMessage());
        }
        agentRunRepository.save(run);
    }

    @Override
    @Transactional
    public void saveRunResult(SaveRunResultRequest request) {
        AgentRun run = findRun(request.tenantId(), request.agentRunId());
        run.setResultPayload(toJson(request.resultPayload()));
        agentRunRepository.save(run);
    }

    private AgentRun findRun(String tenantId, java.util.UUID agentRunId) {
        return agentRunRepository.findByIdAndTenantId(agentRunId, tenantId)
                .orElseThrow(() -> new NotFoundException("Agent run not found: " + agentRunId));
    }

    /** result_payload is a jsonb column; plain assistant text isn't valid JSON on its own. */
    private String toJson(String plainText) {
        try {
            return objectMapper.writeValueAsString(plainText);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ai.pivot360.agents.common.exception.AgentRuntimeException("Failed to serialize run result", e);
        }
    }
}
