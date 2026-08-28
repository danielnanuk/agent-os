-- Global demo agent (tenant_id IS NULL => visible to every tenant).
-- llm_model is a placeholder; adjust to whatever model your OpenAI account/plan
-- currently supports before running the end-to-end verification.
INSERT INTO agent_definitions (
    tenant_id, agent_key, name, description,
    llm_provider, llm_model, system_prompt, max_iterations, tool_names
) VALUES (
    NULL,
    'general-assistant',
    'General Assistant',
    'Demo agent used to verify the end-to-end run/tool-call/streaming pipeline.',
    'OPENAI',
    'gpt-4.1-mini',
    'You are a helpful assistant. Use the available tools when they help answer the user''s request precisely.',
    10,
    '["get_current_time"]'::jsonb
);
