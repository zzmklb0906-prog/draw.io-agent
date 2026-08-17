CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL, display_name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE', roles JSONB NOT NULL DEFAULT '["USER"]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0
);
INSERT INTO app_user(username,password_hash,display_name,roles)
VALUES ('admin', crypt('admin', gen_salt('bf', 10)), 'Administrator', '["ADMIN","USER"]')
ON CONFLICT (username) DO NOTHING;

CREATE TABLE auth_refresh_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES app_user(id),
    token_hash VARCHAR(128) NOT NULL UNIQUE, jwt_id VARCHAR(80) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,
    user_agent VARCHAR(512), ip_address VARCHAR(64), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_workspace (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), owner_user_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(160) NOT NULL, description TEXT, settings JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE conversation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), workspace_id UUID REFERENCES agent_workspace(id),
    user_id UUID NOT NULL REFERENCES app_user(id), agent_id VARCHAR(80) NOT NULL,
    adk_session_id VARCHAR(120) UNIQUE, title VARCHAR(240) NOT NULL DEFAULT '新会话',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', current_invocation_id VARCHAR(120),
    current_checkpoint_id UUID, current_context_snapshot_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at TIMESTAMPTZ, deleted_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE conversation_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    adk_session_id VARCHAR(120), invocation_id VARCHAR(120), sequence_no BIGINT NOT NULL,
    role VARCHAR(24) NOT NULL, message_type VARCHAR(40) NOT NULL DEFAULT 'TEXT',
    content_text TEXT, content_json JSONB, token_count BIGINT, model_name VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(conversation_id, sequence_no)
);

CREATE TABLE agent_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    adk_session_id VARCHAR(120) NOT NULL, title VARCHAR(240) NOT NULL, task_type VARCHAR(60) NOT NULL DEFAULT 'GENERAL',
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED', current_invocation_id VARCHAR(120), current_checkpoint_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE agent_invocation (
    id VARCHAR(120) PRIMARY KEY, task_id UUID REFERENCES agent_task(id), conversation_id UUID REFERENCES conversation(id),
    adk_session_id VARCHAR(120) NOT NULL, parent_invocation_id VARCHAR(120), trigger_type VARCHAR(40) NOT NULL DEFAULT 'USER_MESSAGE',
    root_agent_name VARCHAR(160), status VARCHAR(32) NOT NULL DEFAULT 'RUNNING', request_id VARCHAR(120),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ, duration_ms BIGINT,
    input_tokens BIGINT NOT NULL DEFAULT 0, output_tokens BIGINT NOT NULL DEFAULT 0,
    cached_tokens BIGINT NOT NULL DEFAULT 0, reasoning_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost NUMERIC(18,8), model_call_count INT NOT NULL DEFAULT 0, tool_call_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(80), error_message TEXT, version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE agent_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), invocation_id VARCHAR(120) NOT NULL REFERENCES agent_invocation(id) ON DELETE CASCADE,
    parent_run_id UUID REFERENCES agent_run(id), agent_id VARCHAR(80), agent_name VARCHAR(160) NOT NULL,
    agent_version VARCHAR(80), status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ, duration_ms BIGINT,
    model_call_count INT NOT NULL DEFAULT 0, tool_call_count INT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0, output_tokens BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0, error_code VARCHAR(80), error_message TEXT, version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE agent_run_step (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), invocation_id VARCHAR(120) NOT NULL REFERENCES agent_invocation(id) ON DELETE CASCADE,
    agent_run_id UUID REFERENCES agent_run(id), parent_step_id UUID REFERENCES agent_run_step(id),
    sequence_no BIGINT NOT NULL, step_type VARCHAR(48) NOT NULL, step_name VARCHAR(240) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING', input_summary TEXT, output_summary TEXT,
    metadata JSONB NOT NULL DEFAULT '{}', started_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
    duration_ms BIGINT, retry_count INT NOT NULL DEFAULT 0, error_code VARCHAR(80), error_message TEXT,
    UNIQUE(invocation_id, sequence_no)
);

CREATE TABLE model_call (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), invocation_id VARCHAR(120) NOT NULL REFERENCES agent_invocation(id) ON DELETE CASCADE,
    agent_run_id UUID REFERENCES agent_run(id), step_id UUID REFERENCES agent_run_step(id), provider VARCHAR(80),
    model_name VARCHAR(160) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'RUNNING', streaming BOOLEAN NOT NULL DEFAULT false,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(), first_token_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
    duration_ms BIGINT, time_to_first_token_ms BIGINT, input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0, reasoning_tokens BIGINT NOT NULL DEFAULT 0, cached_input_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost NUMERIC(18,8), finish_reason VARCHAR(80), retry_count INT NOT NULL DEFAULT 0,
    request_summary TEXT, response_summary TEXT, error_code VARCHAR(80), error_message TEXT, metadata JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE artifact (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), workspace_id UUID REFERENCES agent_workspace(id),
    conversation_id UUID REFERENCES conversation(id), invocation_id VARCHAR(120) REFERENCES agent_invocation(id),
    parent_artifact_id UUID REFERENCES artifact(id), artifact_type VARCHAR(64) NOT NULL, name VARCHAR(240) NOT NULL,
    mime_type VARCHAR(160), storage_type VARCHAR(24) NOT NULL DEFAULT 'DATABASE', storage_key TEXT,
    content_text TEXT, content_json JSONB, content_hash VARCHAR(128), size_bytes BIGINT,
    version_no INT NOT NULL DEFAULT 1, status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by UUID REFERENCES app_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tool_execution (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), invocation_id VARCHAR(120) NOT NULL REFERENCES agent_invocation(id) ON DELETE CASCADE,
    agent_run_id UUID REFERENCES agent_run(id), step_id UUID REFERENCES agent_run_step(id), tool_call_id VARCHAR(160) NOT NULL,
    capability_id VARCHAR(240), tool_name VARCHAR(240) NOT NULL, tool_type VARCHAR(48) NOT NULL DEFAULT 'JAVA_TOOL',
    provider_name VARCHAR(160), risk_level VARCHAR(32), status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    arguments_json JSONB, arguments_summary TEXT, result_summary TEXT, result_artifact_id UUID REFERENCES artifact(id),
    result_size_bytes BIGINT, started_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
    duration_ms BIGINT, timeout_ms BIGINT, retry_count INT NOT NULL DEFAULT 0, max_retries INT NOT NULL DEFAULT 0,
    confirmation_required BOOLEAN NOT NULL DEFAULT false, confirmation_status VARCHAR(32), error_code VARCHAR(80), error_message TEXT,
    UNIQUE(invocation_id, tool_call_id)
);

CREATE TABLE tool_execution_attempt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tool_execution_id UUID NOT NULL REFERENCES tool_execution(id) ON DELETE CASCADE,
    attempt_no INT NOT NULL, status VARCHAR(32) NOT NULL, started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ, duration_ms BIGINT, request_summary TEXT, result_summary TEXT,
    error_code VARCHAR(80), error_message TEXT, UNIQUE(tool_execution_id, attempt_no)
);

CREATE TABLE context_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    adk_session_id VARCHAR(120) NOT NULL, parent_snapshot_id UUID REFERENCES context_snapshot(id), snapshot_version INT NOT NULL,
    summary_text TEXT NOT NULL, structured_state JSONB NOT NULL DEFAULT '{}', covered_message_from BIGINT,
    covered_message_to BIGINT, covered_event_from BIGINT, covered_event_to BIGINT, estimated_tokens BIGINT,
    compression_strategy VARCHAR(80), compression_model VARCHAR(160), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(conversation_id, snapshot_version)
);

CREATE TABLE workflow_checkpoint (
    id UUID PRIMARY KEY, conversation_id UUID REFERENCES conversation(id), adk_session_id VARCHAR(120) NOT NULL,
    invocation_id VARCHAR(120), agent_id VARCHAR(80) NOT NULL, user_id UUID REFERENCES app_user(id),
    checkpoint_type VARCHAR(48) NOT NULL DEFAULT 'WORKFLOW_PAUSE', stage VARCHAR(48) NOT NULL,
    status VARCHAR(32) NOT NULL, revision BIGINT NOT NULL DEFAULT 1, state_snapshot JSONB NOT NULL DEFAULT '{}',
    pending_action JSONB, approval_request JSONB, approval_result JSONB, original_prompt TEXT, error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ
);

CREATE TABLE adk_session (
    session_id VARCHAR(120) PRIMARY KEY, app_name VARCHAR(120) NOT NULL, user_key VARCHAR(120) NOT NULL,
    state JSONB NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(app_name, user_key, session_id)
);

CREATE TABLE adk_event (
    id VARCHAR(160) PRIMARY KEY, session_id VARCHAR(120) NOT NULL REFERENCES adk_session(session_id) ON DELETE CASCADE,
    invocation_id VARCHAR(120), sequence_no BIGINT NOT NULL, author VARCHAR(160), event_type VARCHAR(80) NOT NULL DEFAULT 'ADK_EVENT',
    payload JSONB NOT NULL, event_time TIMESTAMPTZ NOT NULL DEFAULT now(), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(session_id, sequence_no)
);

CREATE TABLE agent_runtime_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), session_id VARCHAR(120) NOT NULL, task_id UUID REFERENCES agent_task(id),
    invocation_id VARCHAR(120), agent_run_id UUID REFERENCES agent_run(id), step_id UUID REFERENCES agent_run_step(id),
    sequence_no BIGINT NOT NULL, event_type VARCHAR(80) NOT NULL, event_level VARCHAR(16) NOT NULL DEFAULT 'INFO',
    event_time TIMESTAMPTZ NOT NULL DEFAULT now(), payload JSONB NOT NULL DEFAULT '{}', trace_id VARCHAR(120), span_id VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(session_id, sequence_no)
);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), aggregate_type VARCHAR(80) NOT NULL, aggregate_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(80) NOT NULL, payload JSONB NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), published_at TIMESTAMPTZ
);

CREATE INDEX idx_conversation_user_updated ON conversation(user_id, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_message_conversation_sequence ON conversation_message(conversation_id, sequence_no DESC);
CREATE INDEX idx_invocation_session_started ON agent_invocation(adk_session_id, started_at DESC);
CREATE INDEX idx_run_invocation_started ON agent_run(invocation_id, started_at);
CREATE INDEX idx_step_invocation_sequence ON agent_run_step(invocation_id, sequence_no);
CREATE INDEX idx_tool_invocation_started ON tool_execution(invocation_id, started_at);
CREATE INDEX idx_runtime_session_time ON agent_runtime_event(session_id, event_time DESC);
CREATE INDEX idx_runtime_invocation_sequence ON agent_runtime_event(invocation_id, sequence_no);
CREATE INDEX idx_adk_event_session_sequence ON adk_event(session_id, sequence_no);
CREATE INDEX idx_outbox_pending ON outbox_event(status, created_at) WHERE status = 'PENDING';
