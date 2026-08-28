package ai.pivot360.agents.persistence.entity;

import ai.pivot360.agents.common.agent.LlmProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A named, reusable LLM endpoint configuration that one or more {@link AgentDefinition}s
 * can bind to via {@code llm_model_id} -- adding a model, or repointing an agent at a
 * different one, is a data change (new/updated row), never a code or config-file change.
 * {@code tenantId == null} means the model is global/shared, mirroring {@link AgentDefinition}.
 */
@Entity
@Table(name = "llm_models")
public class LlmModel {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LlmProvider provider;

    @Column(name = "base_url")
    private String baseUrl;

    /** Name of the environment variable holding the API key -- never the raw key itself. */
    @Column(name = "api_key_env")
    private String apiKeyEnv;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LlmModel() {
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public LlmProvider getProvider() {
        return provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKeyEnv() {
        return apiKeyEnv;
    }

    public String getModelName() {
        return modelName;
    }
}
