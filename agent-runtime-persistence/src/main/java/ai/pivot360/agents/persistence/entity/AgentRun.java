package ai.pivot360.agents.persistence.entity;

import ai.pivot360.agents.common.agent.AgentRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_runs")
public class AgentRun {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "agent_definition_id", nullable = false)
    private UUID agentDefinitionId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "workflow_id", nullable = false, unique = true)
    private String workflowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentRunStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_payload", nullable = false)
    private String inputPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_payload")
    private String resultPayload;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentRun() {
    }

    public AgentRun(UUID id, String tenantId, UUID agentDefinitionId, UUID conversationId,
                     String workflowId, String inputPayload) {
        this.id = id;
        this.tenantId = tenantId;
        this.agentDefinitionId = agentDefinitionId;
        this.conversationId = conversationId;
        this.workflowId = workflowId;
        this.status = AgentRunStatus.PENDING;
        this.inputPayload = inputPayload;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getAgentDefinitionId() {
        return agentDefinitionId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public AgentRunStatus getStatus() {
        return status;
    }

    public void setStatus(AgentRunStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
        if (status == AgentRunStatus.RUNNING && startedAt == null) {
            this.startedAt = Instant.now();
        }
        if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELLED) {
            this.completedAt = Instant.now();
        }
    }

    public String getInputPayload() {
        return inputPayload;
    }

    public String getResultPayload() {
        return resultPayload;
    }

    public void setResultPayload(String resultPayload) {
        this.resultPayload = resultPayload;
        this.updatedAt = Instant.now();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
