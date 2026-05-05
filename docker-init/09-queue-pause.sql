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
