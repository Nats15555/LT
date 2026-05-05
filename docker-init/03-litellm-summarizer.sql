
INSERT INTO summarizer_models (id, name, provider, base_url, model_id, api_key_env_var, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'LiteLLM Quality (gpt-4o-mini)', 'OPENAI', 'http://localhost:4000', 'loadtest-summary', 'LITELLM_MASTER_KEY', true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM summarizer_models WHERE name = 'LiteLLM Quality (gpt-4o-mini)');

INSERT INTO summarizer_models (id, name, provider, base_url, model_id, api_key_env_var, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'LiteLLM Fast (gpt-5.4-nano)', 'OPENAI', 'http://localhost:4000', 'loadtest-summary-nano', 'LITELLM_MASTER_KEY', true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM summarizer_models WHERE name = 'LiteLLM Fast (gpt-5.4-nano)');
