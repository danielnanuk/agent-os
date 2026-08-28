CREATE TABLE conversation_messages (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        VARCHAR(128) NOT NULL,
    agent_run_id     UUID NOT NULL REFERENCES agent_runs (id),
    role             VARCHAR(16) NOT NULL,
    content          TEXT,
    tool_name        VARCHAR(128),
    tool_call_id     VARCHAR(128),
    sequence_number  INT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_conversation_messages_run_seq ON conversation_messages (agent_run_id, sequence_number);
CREATE INDEX idx_conversation_messages_tenant_id ON conversation_messages (tenant_id);
