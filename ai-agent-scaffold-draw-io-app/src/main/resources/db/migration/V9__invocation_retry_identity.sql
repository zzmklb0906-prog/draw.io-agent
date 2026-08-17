DROP INDEX IF EXISTS uk_invocation_request_id;

ALTER TABLE agent_invocation ADD COLUMN IF NOT EXISTS attempt_no INT NOT NULL DEFAULT 1;
ALTER TABLE agent_invocation ADD COLUMN IF NOT EXISTS retry_of_invocation_id VARCHAR(120);

CREATE UNIQUE INDEX IF NOT EXISTS uk_invocation_request_attempt
    ON agent_invocation(adk_session_id,request_id,attempt_no)
    WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_invocation_logical_request
    ON agent_invocation(adk_session_id,request_id,started_at DESC)
    WHERE request_id IS NOT NULL;
