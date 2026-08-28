CREATE TABLE agent_runs (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            VARCHAR(128) NOT NULL,
    agent_definition_id  UUID NOT NULL REFERENCES agent_definitions (id),
    workflow_id          VARCHAR(255) NOT NULL,
    status               VARCHAR(32) NOT NULL,
    input_payload        JSONB NOT NULL,
    result_payload       JSONB,
    error_message        TEXT,
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_runs_workflow_id UNIQUE (workflow_id)
);

CREATE INDEX idx_agent_runs_tenant_id ON agent_runs (tenant_id);
