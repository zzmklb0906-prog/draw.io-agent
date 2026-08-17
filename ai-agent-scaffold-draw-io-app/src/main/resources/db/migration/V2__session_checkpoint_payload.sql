ALTER TABLE adk_session ADD COLUMN IF NOT EXISTS session_json TEXT;
ALTER TABLE workflow_checkpoint ADD COLUMN IF NOT EXISTS entity_json JSONB;
