UPDATE agent_definitions
SET tool_names = '["get_current_time", "web_search"]'::jsonb
WHERE agent_key = 'general-assistant' AND tenant_id IS NULL;
