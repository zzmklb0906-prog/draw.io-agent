ALTER TABLE capability_search_candidate ADD COLUMN IF NOT EXISTS schema_version VARCHAR(80);
ALTER TABLE capability_search_candidate ADD COLUMN IF NOT EXISTS content_version VARCHAR(80);
ALTER TABLE capability_execution ADD COLUMN IF NOT EXISTS schema_version VARCHAR(80);
ALTER TABLE capability_execution ADD COLUMN IF NOT EXISTS content_version VARCHAR(80);

ALTER TABLE agent_invocation ADD COLUMN IF NOT EXISTS version_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE agent_invocation ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(80);
ALTER TABLE agent_invocation ADD COLUMN IF NOT EXISTS agent_config_version VARCHAR(80);
ALTER TABLE agent_invocation ADD COLUMN IF NOT EXISTS model_version VARCHAR(80);
