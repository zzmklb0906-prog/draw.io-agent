CREATE TABLE workspace_member (
    workspace_id UUID NOT NULL REFERENCES agent_workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role VARCHAR(24) NOT NULL CHECK(role IN('OWNER','EDITOR','VIEWER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(workspace_id,user_id)
);
INSERT INTO workspace_member(workspace_id,user_id,role)
SELECT id,owner_user_id,'OWNER' FROM agent_workspace ON CONFLICT DO NOTHING;

CREATE TABLE security_audit_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    username VARCHAR(80),
    action VARCHAR(120) NOT NULL,
    resource_type VARCHAR(80),
    resource_id VARCHAR(160),
    outcome VARCHAR(24) NOT NULL,
    ip_address VARCHAR(64),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_security_audit_user_time ON security_audit_event(username,occurred_at DESC);
