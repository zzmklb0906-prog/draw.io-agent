CREATE TABLE runtime_instance (
    instance_id VARCHAR(120) PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

ALTER TABLE agent_invocation ADD COLUMN IF NOT EXISTS worker_instance_id VARCHAR(120);
ALTER TABLE agent_invocation ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ;
ALTER TABLE agent_invocation ADD COLUMN IF NOT EXISTS recoverable BOOLEAN NOT NULL DEFAULT true;
CREATE INDEX IF NOT EXISTS idx_invocation_recovery_scan
    ON agent_invocation(status,heartbeat_at) WHERE status='RUNNING';

ALTER TABLE tool_execution ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ;
ALTER TABLE tool_execution ADD COLUMN IF NOT EXISTS timeout_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_tool_recovery_scan
    ON tool_execution(status,heartbeat_at) WHERE status='RUNNING';

CREATE TABLE workflow_recovery_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    checkpoint_id UUID NOT NULL REFERENCES workflow_checkpoint(id) ON DELETE CASCADE,
    source_invocation_id VARCHAR(120) REFERENCES agent_invocation(id) ON DELETE SET NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(80) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_by VARCHAR(120),
    claimed_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(source_invocation_id)
);
CREATE INDEX idx_recovery_job_pending ON workflow_recovery_job(status,available_at);
