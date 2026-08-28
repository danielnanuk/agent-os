CREATE TABLE agent_definitions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      VARCHAR(128),
    agent_key      VARCHAR(128) NOT NULL,
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    llm_provider   VARCHAR(32) NOT NULL,
    llm_model      VARCHAR(128) NOT NULL,
    system_prompt  TEXT,
    max_iterations INT NOT NULL DEFAULT 10,
    tool_names     JSONB NOT NULL DEFAULT '[]'::jsonb,
    version        INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- NULLS NOT DISTINCT so (tenant_id=NULL, agent_key='x') can only exist once (global agent),
-- while each tenant may still define its own private agent with the same agent_key.
CREATE UNIQUE INDEX uq_agent_definitions_tenant_key
    ON agent_definitions (tenant_id, agent_key) NULLS NOT DISTINCT;

CREATE INDEX idx_agent_definitions_tenant_id ON agent_definitions (tenant_id);
