package ai.pivot360.agents.api.service;

import ai.pivot360.agents.common.activity.ChatMessageDto;
import ai.pivot360.agents.common.agent.LlmModelConfig;
import ai.pivot360.agents.common.agent.MessageRole;
import ai.pivot360.agents.common.exception.AgentRuntimeException;
import ai.pivot360.agents.common.exception.NotFoundException;
import ai.pivot360.agents.common.workflow.AgentRunInput;
import ai.pivot360.agents.persistence.entity.AgentDefinition;
import ai.pivot360.agents.persistence.entity.AgentRun;
import ai.pivot360.agents.persistence.entity.Conversation;
import ai.pivot360.agents.persistence.entity.ConversationMessage;
import ai.pivot360.agents.persistence.entity.LlmModel;
import ai.pivot360.agents.persistence.repository.AgentDefinitionRepository;
import ai.pivot360.agents.persistence.repository.AgentRunRepository;
import ai.pivot360.agents.persistence.repository.ConversationMessageRepository;
import ai.pivot360.agents.persistence.repository.ConversationRepository;
import ai.pivot360.agents.persistence.repository.LlmModelRepository;
import ai.pivot360.agents.temporal.TemporalQueues;
import ai.pivot360.agents.temporal.WorkflowIdFactory;
import ai.pivot360.agents.temporal.workflow.AgentRunWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.SearchAttributes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static ai.pivot360.agents.temporal.SearchAttributeKeys.AGENT_KEY;
import static ai.pivot360.agents.temporal.SearchAttributeKeys.TENANT_ID;

/**
 * Single entry point for starting/reading agent runs, shared by both the REST
 * controller and the MCP server tools ({@code AgentInvocationTools}) so the two
 * entry points never duplicate orchestration logic.
 */
@Service
public class AgentRunService {

    private final AgentDefinitionRepository agentDefinitionRepository;
    private final LlmModelRepository llmModelRepository;
    private final AgentRunRepository agentRunRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;
    private final TenantService tenantService;
    private final WorkflowClient workflowClient;
    private final ObjectMapper objectMapper;

    public AgentRunService(AgentDefinitionRepository agentDefinitionRepository,
                            LlmModelRepository llmModelRepository,
                            AgentRunRepository agentRunRepository,
                            ConversationRepository conversationRepository,
                            ConversationMessageRepository conversationMessageRepository,
                            TenantService tenantService,
                            WorkflowClient workflowClient,
                            ObjectMapper objectMapper) {
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.llmModelRepository = llmModelRepository;
        this.agentRunRepository = agentRunRepository;
        this.conversationRepository = conversationRepository;
        this.conversationMessageRepository = conversationMessageRepository;
        this.tenantService = tenantService;
        this.workflowClient = workflowClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StartRunResult startRun(String tenantId, String agentKey, String taskInput, UUID conversationId) {
        tenantService.getOrCreate(tenantId);

        AgentDefinition definition = agentDefinitionRepository.findForTenant(tenantId, agentKey)
                .orElseThrow(() -> new NotFoundException("No agent definition found for key: " + agentKey));
        LlmModel llmModel = llmModelRepository.findById(definition.getLlmModelId())
                .orElseThrow(() -> new NotFoundException("No LLM model found for id: " + definition.getLlmModelId()));
        LlmModelConfig llmModelConfig = new LlmModelConfig(
                llmModel.getProvider(), llmModel.getBaseUrl(), llmModel.getApiKeyEnv(), llmModel.getModelName());

        Conversation conversation = conversationId != null
                ? conversationRepository.findByIdAndTenantId(conversationId, tenantId)
                        .orElseThrow(() -> new NotFoundException("Conversation not found: " + conversationId))
                : conversationRepository.save(new Conversation(tenantId));
        List<ChatMessageDto> priorHistory = buildPriorHistory(tenantId, conversation.getId());

        UUID agentRunId = UUID.randomUUID();
        String workflowId = WorkflowIdFactory.forAgentRun(tenantId, agentRunId);

        String inputPayloadJson = writeJson(taskInput);
        AgentRun run = new AgentRun(agentRunId, tenantId, definition.getId(), conversation.getId(), workflowId, inputPayloadJson);
        agentRunRepository.save(run);

        AgentRunInput workflowInput = new AgentRunInput(
                tenantId, agentRunId, definition.getId(), agentKey, taskInput,
                llmModelConfig, definition.getSystemPrompt(),
                definition.getMaxIterations(), definition.getToolNames(), priorHistory);

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(TemporalQueues.AGENT_RUNTIME_DEFAULT)
                .setWorkflowExecutionTimeout(Duration.ofHours(1))
                .setTypedSearchAttributes(SearchAttributes.newBuilder()
                        .set(TENANT_ID, tenantId)
                        .set(AGENT_KEY, agentKey)
                        .build())
                .build();

        AgentRunWorkflow workflow = workflowClient.newWorkflowStub(AgentRunWorkflow.class, options);
        WorkflowClient.start(workflow::run, workflowInput);

        return new StartRunResult(agentRunId, conversation.getId());
    }

    /**
     * Only USER inputs and final (non-blank) ASSISTANT answers carry forward into a new
     * turn's context -- intermediate tool-call-only assistant messages and raw TOOL
     * results are noise for cross-turn memory (the model already acted on them in the
     * turn where they happened).
     */
    private List<ChatMessageDto> buildPriorHistory(String tenantId, UUID conversationId) {
        List<UUID> priorRunIds = agentRunRepository.findByConversationIdAndTenantIdOrderByCreatedAtAsc(conversationId, tenantId)
                .stream()
                .map(AgentRun::getId)
                .toList();
        if (priorRunIds.isEmpty()) {
            return List.of();
        }
        return conversationMessageRepository.findByAgentRunIdInAndTenantIdOrderByCreatedAtAsc(priorRunIds, tenantId).stream()
                .filter(m -> m.getRole() == MessageRole.USER
                        || (m.getRole() == MessageRole.ASSISTANT && m.getContent() != null && !m.getContent().isBlank()))
                .map(m -> m.getRole() == MessageRole.USER
                        ? ChatMessageDto.user(m.getContent())
                        : ChatMessageDto.assistant(m.getContent(), null))
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentRun getRun(String tenantId, UUID agentRunId) {
        return agentRunRepository.findByIdAndTenantId(agentRunId, tenantId)
                .orElseThrow(() -> new NotFoundException("Agent run not found: " + agentRunId));
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> getMessages(String tenantId, UUID agentRunId) {
        return conversationMessageRepository.findByAgentRunIdAndTenantIdOrderBySequenceNumberAsc(agentRunId, tenantId);
    }

    private String writeJson(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AgentRuntimeException("Failed to serialize input payload", e);
        }
    }
}
