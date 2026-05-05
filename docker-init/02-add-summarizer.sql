
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'test_task' AND column_name = 'summarizer_name') THEN
    ALTER TABLE test_task ADD COLUMN summarizer_name VARCHAR(64);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'test_task_history' AND column_name = 'summarizer_name') THEN
    ALTER TABLE test_task_history ADD COLUMN summarizer_name VARCHAR(64);
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS summarizer_models (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(64) NOT NULL UNIQUE,
    base_url TEXT,
    model_id VARCHAR(128) NOT NULL,
    api_key_env_var VARCHAR(128),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_summarizer_models_name ON summarizer_models(name);
CREATE INDEX IF NOT EXISTS idx_summarizer_models_enabled ON summarizer_models(enabled);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'summarizer_models' AND column_name = 'provider') THEN
    ALTER TABLE summarizer_models ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'OPENAI';
  END IF;
END $$;

