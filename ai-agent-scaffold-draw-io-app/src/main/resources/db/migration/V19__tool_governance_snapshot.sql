ALTER TABLE tool_execution ADD COLUMN IF NOT EXISTS governance_policy JSONB NOT NULL DEFAULT '{}'::jsonb;

