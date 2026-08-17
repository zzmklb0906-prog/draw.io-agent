CREATE TABLE workflow_state_transition (
    task_id UUID NOT NULL REFERENCES agent_task(id) ON DELETE CASCADE,
    sequence_no BIGINT NOT NULL,
    checkpoint_id UUID REFERENCES workflow_checkpoint(id) ON DELETE SET NULL,
    invocation_id VARCHAR(120) REFERENCES agent_invocation(id) ON DELETE SET NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY(task_id,sequence_no)
);
CREATE INDEX idx_workflow_transition_time ON workflow_state_transition(task_id,occurred_at);
