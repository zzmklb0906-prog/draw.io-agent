ALTER TABLE agent_template ADD COLUMN IF NOT EXISTS token_budget BIGINT NOT NULL DEFAULT 50000;
ALTER TABLE dynamic_subagent_task ADD COLUMN IF NOT EXISTS parent_task_id UUID REFERENCES dynamic_subagent_task(id) ON DELETE SET NULL;
ALTER TABLE dynamic_subagent_task ADD COLUMN IF NOT EXISTS depth INT NOT NULL DEFAULT 1;
ALTER TABLE dynamic_subagent_task ADD COLUMN IF NOT EXISTS dependencies JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE dynamic_subagent_task ADD COLUMN IF NOT EXISTS token_budget BIGINT NOT NULL DEFAULT 50000;
ALTER TABLE dynamic_subagent_task ADD COLUMN IF NOT EXISTS input_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dynamic_subagent_task ADD COLUMN IF NOT EXISTS output_tokens BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_dynamic_subagent_parent ON dynamic_subagent_task(parent_task_id,created_at);
