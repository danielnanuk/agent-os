package ai.pivot360.agents.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A pluggable agent "type" registered in the runtime. {@code tenantId == null} means
 * the definition is global/shared across all tenants; a non-null value scopes it to
 * one tenant's private agent.
 */
@Entity
@Table(name = "agent_definitions")
public class AgentDefinition {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "agent_key", nullable = false)
    private String agentKey;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** References {@code llm_models.id} -- see {@link LlmModel}. */
    @Column(name = "llm_model_id", nullable = false)
    private UUID llmModelId;

    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "max_iterations", nullable = false)
    private int maxIterations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_names", nullable = false)
    private List<String> toolNames;

    @Version
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentDefinition() {
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getAgentKey() {
        return agentKey;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public UUID getLlmModelId() {
        return llmModelId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public List<String> getToolNames() {
        return toolNames;
    }
}
