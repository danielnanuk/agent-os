-- Multi-turn conversation memory: a conversation groups several independent agent_runs
-- (one per turn) so a new run can be seeded with the prior turns' history instead of
-- starting cold each time.
CREATE TABLE conversations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(128) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE agent_runs ADD COLUMN conversation_id UUID REFERENCES conversations (id);
CREATE INDEX idx_agent_runs_conversation_id ON agent_runs (conversation_id);
