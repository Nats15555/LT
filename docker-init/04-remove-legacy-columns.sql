ALTER TABLE test_task DROP COLUMN IF EXISTS pr_id;
ALTER TABLE test_task_history DROP COLUMN IF EXISTS pr_id;

ALTER TABLE summarizer_models DROP COLUMN IF EXISTS extra_config;

DROP TABLE IF EXISTS summary_report_templates;
