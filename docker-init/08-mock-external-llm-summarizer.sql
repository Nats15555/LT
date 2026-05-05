INSERT INTO summarizer_models (id, name, provider, base_url, model_id, api_key_env_var, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Mock external LLM (8095)', 'EXTERNAL',
       'http://localhost:8095/api/v1/external-llm/ingest', 'external', NULL, true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM summarizer_models WHERE name = 'Mock external LLM (8095)');

UPDATE summarizer_models
SET base_url = 'http://localhost:8095/api/v1/external-llm/ingest', updated_at = now()
WHERE name = 'Mock external LLM (8095)' AND provider = 'EXTERNAL'
  AND (base_url IS NULL OR TRIM(base_url) = '' OR base_url LIKE '%external-llm-mock%');
