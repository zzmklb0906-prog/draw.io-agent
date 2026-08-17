CREATE TABLE capability_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invocation_id VARCHAR(120) NOT NULL REFERENCES agent_invocation(id) ON DELETE CASCADE,
    search_id UUID REFERENCES capability_search(id) ON DELETE SET NULL,
    capability_id VARCHAR(320),
    judgment VARCHAR(40) NOT NULL CHECK(judgment IN('REQUIRED_MISSED','WRONG_SELECTION','IMPACTED_OUTPUT','NO_IMPACT')),
    note VARCHAR(2000),
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_capability_feedback_invocation ON capability_feedback(invocation_id,created_at);

