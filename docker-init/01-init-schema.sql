
CREATE TABLE IF NOT EXISTS test_task (
    id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(128),
    test_tool VARCHAR(32) NOT NULL,
    test_file_name TEXT NOT NULL,
    test_file_content_base64 TEXT NOT NULL,
    command TEXT NOT NULL,
    expected_duration_seconds INTEGER,
    metrics_config JSONB,
    error_message TEXT,
    summarizer_name VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_test_task_status_created_at
    ON test_task(status, created_at);

CREATE TABLE IF NOT EXISTS test_task_history (
    id UUID PRIMARY KEY,
    final_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    moved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    test_tool VARCHAR(32) NOT NULL,
    test_file_name TEXT NOT NULL,
    test_file_content_base64 TEXT NOT NULL,
    command TEXT NOT NULL,
    expected_duration_seconds INTEGER,
    metrics_config JSONB,
    error_message TEXT,
    summarizer_name VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_test_task_history_created_at
    ON test_task_history(created_at);

CREATE TABLE IF NOT EXISTS test_artifacts (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES test_task_history(id) ON DELETE CASCADE,
    file_name TEXT NOT NULL,
    content_encoding VARCHAR(16) NOT NULL DEFAULT 'gzip',
    file_content BYTEA NOT NULL,
    original_size_bytes BIGINT,
    compressed_size_bytes BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_test_artifacts_task_id ON test_artifacts(task_id);
CREATE INDEX IF NOT EXISTS idx_test_artifacts_file_name ON test_artifacts(file_name);

CREATE TABLE IF NOT EXISTS test_metrics (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES test_task_history(id) ON DELETE CASCADE,
    source_type VARCHAR(32) NOT NULL,
    endpoint_url TEXT NOT NULL,
    query_params TEXT,
    metrics_data JSONB NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_test_metrics_task_id ON test_metrics(task_id);
CREATE INDEX IF NOT EXISTS idx_test_metrics_source_type ON test_metrics(source_type);
CREATE INDEX IF NOT EXISTS idx_test_metrics_collected_at ON test_metrics(collected_at);

CREATE TABLE IF NOT EXISTS test_summary (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES test_task_history(id) ON DELETE CASCADE,
    summary_type VARCHAR(32) NOT NULL,
    summary_data JSONB NOT NULL,
    processing_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_test_summary_task_id ON test_summary(task_id);
CREATE INDEX IF NOT EXISTS idx_test_summary_status ON test_summary(processing_status);
CREATE INDEX IF NOT EXISTS idx_test_summary_created_at ON test_summary(created_at);

       CREATE TABLE IF NOT EXISTS load_test_tools (
           id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
           name VARCHAR(64) NOT NULL UNIQUE,
           docker_image VARCHAR(256) NOT NULL,
           file_extensions TEXT[] NOT NULL,
           enabled BOOLEAN NOT NULL DEFAULT true,
           created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
           updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
       );

CREATE INDEX IF NOT EXISTS idx_load_test_tools_name ON load_test_tools(name);
CREATE INDEX IF NOT EXISTS idx_load_test_tools_enabled ON load_test_tools(enabled);

CREATE TABLE IF NOT EXISTS summarizer_models (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(64) NOT NULL UNIQUE,
    provider VARCHAR(32) NOT NULL DEFAULT 'OPENAI',
    base_url TEXT,
    model_id VARCHAR(128) NOT NULL,
    api_key_env_var VARCHAR(128),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_summarizer_models_name ON summarizer_models(name);
CREATE INDEX IF NOT EXISTS idx_summarizer_models_enabled ON summarizer_models(enabled);

INSERT INTO summarizer_models (id, name, provider, base_url, model_id, api_key_env_var, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'LiteLLM Quality (gpt-4o-mini)', 'OPENAI', 'http://localhost:4000', 'loadtest-summary', 'LITELLM_MASTER_KEY', true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM summarizer_models WHERE name = 'LiteLLM Quality (gpt-4o-mini)');

INSERT INTO summarizer_models (id, name, provider, base_url, model_id, api_key_env_var, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'LiteLLM Fast (gpt-5.4-nano)', 'OPENAI', 'http://localhost:4000', 'loadtest-summary-nano', 'LITELLM_MASTER_KEY', true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM summarizer_models WHERE name = 'LiteLLM Fast (gpt-5.4-nano)');


CREATE TABLE IF NOT EXISTS kafka_outbox (
    id UUID PRIMARY KEY,
    module VARCHAR(32) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kafka_outbox_retry
    ON kafka_outbox(module, status, next_attempt_at, created_at);

CREATE TABLE IF NOT EXISTS loadtest_system_settings (
    id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    queue_paused BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO loadtest_system_settings (id, queue_paused) VALUES (1, false)
    ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS test_task_kafka_pending (
    task_id UUID PRIMARY KEY REFERENCES test_task(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_test_task_kafka_pending_created_at ON test_task_kafka_pending(created_at);
