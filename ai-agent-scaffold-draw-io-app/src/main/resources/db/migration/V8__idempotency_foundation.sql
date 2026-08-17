CREATE TABLE idempotency_record (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_key VARCHAR(160) NOT NULL,
    operation_scope VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    resource_id VARCHAR(160),
    response_json JSONB,
    error_message TEXT,
    attempt_count INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE(owner_key,operation_scope,idempotency_key)
);
CREATE INDEX idx_idempotency_expiry ON idempotency_record(status,expires_at);

ALTER TABLE conversation_message ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(160);
CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_message_idempotency
    ON conversation_message(conversation_id,idempotency_key) WHERE idempotency_key IS NOT NULL;

ALTER TABLE artifact ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(160);
CREATE UNIQUE INDEX IF NOT EXISTS uk_artifact_idempotency
    ON artifact(conversation_id,idempotency_key) WHERE idempotency_key IS NOT NULL;

ALTER TABLE tool_execution ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(200);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tool_execution_idempotency
    ON tool_execution(idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_invocation_request_id
    ON agent_invocation(adk_session_id,request_id) WHERE request_id IS NOT NULL;
