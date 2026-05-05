
INSERT INTO load_test_tools (id, name, docker_image, file_extensions, enabled, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'LOCUST',
    'locustio/locust:latest',
    ARRAY['.py'],
    true,
    now(),
    now()
) ON CONFLICT (name) DO UPDATE SET
    docker_image = EXCLUDED.docker_image,
    file_extensions = EXCLUDED.file_extensions,
    updated_at = now();

INSERT INTO load_test_tools (id, name, docker_image, file_extensions, enabled, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'K6',
    'grafana/k6:latest',
    ARRAY['.js'],
    true,
    now(),
    now()
) ON CONFLICT (name) DO UPDATE SET
    docker_image = EXCLUDED.docker_image,
    file_extensions = EXCLUDED.file_extensions,
    updated_at = now();

INSERT INTO load_test_tools (id, name, docker_image, file_extensions, enabled, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'JMETER',
    'justb4/jmeter:latest',
    ARRAY['.jmx'],
    true,
    now(),
    now()
) ON CONFLICT (name) DO UPDATE SET
    docker_image = EXCLUDED.docker_image,
    file_extensions = EXCLUDED.file_extensions,
    updated_at = now();
