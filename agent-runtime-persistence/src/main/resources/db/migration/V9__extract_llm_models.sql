-- Models become a named, reusable, DB-driven entity instead of two raw columns baked
-- into every agent_definitions row -- adding a model, or repointing an agent at a
-- different one, is now a data change, not a code/config-file change + restart.
CREATE TABLE llm_models (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    VARCHAR(128),
    name         VARCHAR(128) NOT NULL,
    provider     VARCHAR(32) NOT NULL,
    base_url     VARCHAR(255),
    api_key_env  VARCHAR(128),
    model_name   VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Matches whatever general-assistant is actually pointed at right now (see
-- OPENAI_CHAT_BASE_URL/OPENAI_CHAT_API_KEY/OPENAI_CHAT_MODEL in application.yml) so the
-- cutover doesn't require re-exporting any environment variables.
INSERT INTO llm_models (id, tenant_id, name, provider, base_url, api_key_env, model_name)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    NULL,
    'deepseek-v4-flash',
    'OPENAI',
    'https://api.deepseek.com',
    'OPENAI_CHAT_API_KEY',
    'deepseek-v4-flash'
);

ALTER TABLE agent_definitions ADD COLUMN llm_model_id UUID REFERENCES llm_models (id);

UPDATE agent_definitions
SET llm_model_id = '11111111-1111-1111-1111-111111111111'
WHERE agent_key = 'general-assistant' AND tenant_id IS NULL;

ALTER TABLE agent_definitions ALTER COLUMN llm_model_id SET NOT NULL;
ALTER TABLE agent_definitions DROP COLUMN llm_provider;
ALTER TABLE agent_definitions DROP COLUMN llm_model;
