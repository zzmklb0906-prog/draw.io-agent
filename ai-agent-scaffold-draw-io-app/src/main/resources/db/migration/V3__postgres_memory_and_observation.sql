CREATE TABLE agent_memory (
    memory_id VARCHAR(120) PRIMARY KEY,
    user_key VARCHAR(120) NOT NULL,
    project_id VARCHAR(160),
    memory_type VARCHAR(60) NOT NULL,
    content TEXT NOT NULL,
    structured_data JSONB NOT NULL DEFAULT '{}',
    importance DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    confirmed BOOLEAN NOT NULL DEFAULT false,
    source_session_id VARCHAR(120),
    source_event_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    deleted BOOLEAN NOT NULL DEFAULT false,
    search_document TSVECTOR GENERATED ALWAYS AS
      (to_tsvector('simple', coalesce(content,'') || ' ' || coalesce(structured_data::text,''))) STORED
);

CREATE TABLE agent_memory_evidence (
    memory_id VARCHAR(120) NOT NULL REFERENCES agent_memory(memory_id) ON DELETE CASCADE,
    evidence_type VARCHAR(40) NOT NULL,
    evidence_id VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(memory_id,evidence_type,evidence_id)
);

CREATE INDEX idx_memory_user_project_updated ON agent_memory(user_key,project_id,updated_at DESC) WHERE deleted=false;
CREATE INDEX idx_memory_search_document ON agent_memory USING GIN(search_document);
CREATE INDEX idx_model_call_invocation_started ON model_call(invocation_id,started_at);
CREATE INDEX idx_tool_attempt_execution ON tool_execution_attempt(tool_execution_id,attempt_no);
