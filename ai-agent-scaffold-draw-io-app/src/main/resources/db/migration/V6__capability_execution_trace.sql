CREATE TABLE capability_search (
    id UUID PRIMARY KEY,
    invocation_id VARCHAR(120) NOT NULL REFERENCES agent_invocation(id) ON DELETE CASCADE,
    agent_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
    parent_tool_call_id VARCHAR(160),
    snapshot_id VARCHAR(120) NOT NULL,
    agent_name VARCHAR(160) NOT NULL,
    query TEXT NOT NULL,
    requested_types JSONB NOT NULL DEFAULT '[]',
    registry_size INTEGER NOT NULL DEFAULT 0,
    result_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_message TEXT,
    UNIQUE(invocation_id,snapshot_id)
);

CREATE TABLE capability_search_candidate (
    search_id UUID NOT NULL REFERENCES capability_search(id) ON DELETE CASCADE,
    rank_no INTEGER NOT NULL,
    capability_id VARCHAR(240) NOT NULL,
    capability_type VARCHAR(60) NOT NULL,
    capability_group VARCHAR(160) NOT NULL,
    capability_name VARCHAR(200) NOT NULL,
    capability_version INTEGER NOT NULL,
    risk_level VARCHAR(60) NOT NULL,
    score DOUBLE PRECISION NOT NULL DEFAULT 0,
    selected BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY(search_id,rank_no)
);

CREATE TABLE capability_execution (
    id UUID PRIMARY KEY,
    invocation_id VARCHAR(120) NOT NULL REFERENCES agent_invocation(id) ON DELETE CASCADE,
    agent_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
    parent_tool_call_id VARCHAR(160),
    snapshot_id VARCHAR(120) NOT NULL,
    action VARCHAR(32) NOT NULL,
    capability_id VARCHAR(240) NOT NULL,
    capability_type VARCHAR(60) NOT NULL,
    capability_group VARCHAR(160) NOT NULL,
    capability_name VARCHAR(200) NOT NULL,
    capability_version INTEGER NOT NULL,
    risk_level VARCHAR(60) NOT NULL,
    resource_path TEXT,
    arguments JSONB NOT NULL DEFAULT '{}',
    result_summary TEXT,
    result_size BIGINT NOT NULL DEFAULT 0,
    result_hash VARCHAR(80),
    result_artifact_id UUID REFERENCES artifact(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT
);

CREATE INDEX idx_capability_search_invocation ON capability_search(invocation_id,started_at);
CREATE INDEX idx_capability_candidate_id ON capability_search_candidate(capability_id,selected);
CREATE INDEX idx_capability_execution_invocation ON capability_execution(invocation_id,started_at);
CREATE INDEX idx_capability_execution_identity ON capability_execution(capability_type,capability_name,action,status);

UPDATE eval_case c
SET expectations=jsonb_set(c.expectations,'{requiredCapabilities}','[{"type":"SKILL","name":"drawio","action":"EXECUTE"}]'::jsonb,true),
    version=c.version+1,
    updated_at=now()
FROM eval_dataset d
WHERE c.dataset_id=d.id AND d.owner_username='admin' AND d.dataset_key='starter-regression' AND c.case_key='drawio-login-flow';
