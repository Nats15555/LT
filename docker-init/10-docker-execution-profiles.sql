CREATE TABLE IF NOT EXISTS docker_execution_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(128) NOT NULL,
    docker_host_uri TEXT,
    named_volume_for_child_binds VARCHAR(512),
    memory_limit_mb INTEGER,
    memory_reservation_mb INTEGER,
    cpu_limit DECIMAL(5,2),
    cpu_shares INTEGER,
    max_concurrent_containers INTEGER NOT NULL DEFAULT 1,
    network_mode VARCHAR(64) DEFAULT 'bridge',
    restart_policy VARCHAR(32) DEFAULT 'no',
    restart_max_retries INTEGER,
    log_driver VARCHAR(32) DEFAULT 'json-file',
    log_max_size VARCHAR(16),
    log_max_files INTEGER,
    environment_variables JSONB,
    labels JSONB,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_docker_execution_profile_name UNIQUE (name),
    CONSTRAINT chk_docker_profile_max_concurrent CHECK (max_concurrent_containers >= 1)
);

CREATE INDEX IF NOT EXISTS idx_docker_execution_profile_enabled ON docker_execution_profile(enabled);

INSERT INTO docker_execution_profile (
    name, docker_host_uri, named_volume_for_child_binds,
    memory_limit_mb, memory_reservation_mb, cpu_limit, cpu_shares, max_concurrent_containers,
    network_mode, restart_policy, restart_max_retries, log_driver, log_max_size, log_max_files,
    environment_variables, labels, enabled, created_at, updated_at
)
SELECT
    'Default', NULL, NULL,
    512, 256, 0.5, 512, 3,
    'loadtest_loadtest-network', 'no', NULL, 'json-file', '10m', 3,
    NULL, NULL, true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM docker_execution_profile);

INSERT INTO docker_execution_profile (
    name, docker_host_uri, named_volume_for_child_binds,
    memory_limit_mb, memory_reservation_mb, cpu_limit, cpu_shares, max_concurrent_containers,
    network_mode, restart_policy, restart_max_retries, log_driver, log_max_size, log_max_files,
    environment_variables, labels, enabled, created_at, updated_at
)
SELECT
    'Alternate (1 slot)', NULL, NULL,
    dep.memory_limit_mb, dep.memory_reservation_mb, dep.cpu_limit, dep.cpu_shares, 1,
    COALESCE(NULLIF(TRIM(dep.network_mode), ''), 'bridge'),
    COALESCE(NULLIF(TRIM(dep.restart_policy), ''), 'no'), dep.restart_max_retries,
    COALESCE(NULLIF(TRIM(dep.log_driver), ''), 'json-file'), dep.log_max_size, dep.log_max_files,
    dep.environment_variables, dep.labels, true, now(), now()
FROM docker_execution_profile dep
WHERE dep.name = 'Default'
  AND NOT EXISTS (SELECT 1 FROM docker_execution_profile WHERE name = 'Alternate (1 slot)')
LIMIT 1;

ALTER TABLE test_task ADD COLUMN IF NOT EXISTS docker_execution_profile_id UUID;

UPDATE test_task SET docker_execution_profile_id = COALESCE(
    (SELECT id FROM docker_execution_profile WHERE name = 'Default' ORDER BY created_at LIMIT 1),
    (SELECT id FROM docker_execution_profile ORDER BY created_at LIMIT 1)
) WHERE docker_execution_profile_id IS NULL;

ALTER TABLE test_task ALTER COLUMN docker_execution_profile_id SET NOT NULL;

DO $$
BEGIN
    ALTER TABLE test_task
        ADD CONSTRAINT fk_test_task_docker_execution_profile
        FOREIGN KEY (docker_execution_profile_id) REFERENCES docker_execution_profile (id);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

ALTER TABLE test_task_history ADD COLUMN IF NOT EXISTS docker_execution_profile_id UUID;
ALTER TABLE test_task_history ADD COLUMN IF NOT EXISTS docker_profile_name VARCHAR(128);

DO $$
BEGIN
    ALTER TABLE test_task_history
        ADD CONSTRAINT fk_test_task_history_docker_execution_profile
        FOREIGN KEY (docker_execution_profile_id) REFERENCES docker_execution_profile (id) ON DELETE SET NULL;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;
