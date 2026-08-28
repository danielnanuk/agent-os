package ai.pivot360.agents.persistence.entity;

import ai.pivot360.agents.common.agent.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversation_messages")
public class ConversationMessage {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "agent_run_id", nullable = false)
    private UUID agentRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "tool_name")
    private String toolName;

    @Column(name = "tool_call_id")
    private String toolCallId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversationMessage() {
    }

    public ConversationMessage(String tenantId, UUID agentRunId, MessageRole role, String content,
                                String toolName, String toolCallId, int sequenceNumber) {
        this.tenantId = tenantId;
        this.agentRunId = agentRunId;
        this.role = role;
        this.content = content;
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.sequenceNumber = sequenceNumber;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getAgentRunId() {
        return agentRunId;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
