-- V23: Durable Chat-Stream Run and Event Journal Tables

CREATE TABLE IF NOT EXISTS chat_stream_run (
    run_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64),
    agent_id VARCHAR(64),
    checkpoint_id VARCHAR(64),
    checkpoint_revision BIGINT,
    idempotency_key VARCHAR(160),
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    last_sequence_no BIGINT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_csr_user_session ON chat_stream_run(user_id, session_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_csr_user_conv ON chat_stream_run(user_id, conversation_id, updated_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_csr_user_idempotency ON chat_stream_run(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_csr_status ON chat_stream_run(status);

CREATE TABLE IF NOT EXISTS chat_stream_event (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES chat_stream_run(run_id) ON DELETE CASCADE,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    data_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_chat_stream_event_run_seq UNIQUE (run_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS idx_cse_run_seq ON chat_stream_event(run_id, sequence_no);
