INSERT INTO summarizer_models (id, name, provider, base_url, model_id, api_key_env_var, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'External LLM (callback)', 'EXTERNAL',
       'http://localhost:8095/api/v1/external-llm/ingest', 'external', NULL, true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM summarizer_models WHERE name = 'External LLM (callback)');

UPDATE summarizer_models
SET base_url = 'http://localhost:8095/api/v1/external-llm/ingest'
WHERE name = 'External LLM (callback)' AND provider = 'EXTERNAL'
  AND (base_url IS NULL OR TRIM(base_url) = '' OR base_url LIKE '%external-llm-mock%');
