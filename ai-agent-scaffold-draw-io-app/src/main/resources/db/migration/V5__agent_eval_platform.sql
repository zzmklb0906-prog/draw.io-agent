CREATE TABLE eval_dataset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_username VARCHAR(120) NOT NULL,
    dataset_key VARCHAR(120) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT true,
    baseline_run_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(owner_username,dataset_key)
);

CREATE TABLE eval_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES eval_dataset(id) ON DELETE CASCADE,
    case_key VARCHAR(160) NOT NULL,
    name VARCHAR(240) NOT NULL,
    agent_id VARCHAR(80) NOT NULL,
    prompt TEXT NOT NULL,
    expectations JSONB NOT NULL DEFAULT '{}',
    rubric JSONB NOT NULL DEFAULT '{}',
    tags JSONB NOT NULL DEFAULT '[]',
    enabled BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(dataset_id,case_key)
);

CREATE TABLE eval_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES eval_dataset(id) ON DELETE RESTRICT,
    owner_username VARCHAR(120) NOT NULL,
    candidate_label VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    repeat_count INTEGER NOT NULL DEFAULT 1,
    baseline_run_id UUID REFERENCES eval_run(id),
    total_cases INTEGER NOT NULL DEFAULT 0,
    completed_cases INTEGER NOT NULL DEFAULT 0,
    passed_cases INTEGER NOT NULL DEFAULT 0,
    failed_cases INTEGER NOT NULL DEFAULT 0,
    average_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    average_duration_ms BIGINT NOT NULL DEFAULT 0,
    total_input_tokens BIGINT NOT NULL DEFAULT 0,
    total_output_tokens BIGINT NOT NULL DEFAULT 0,
    regression_count INTEGER NOT NULL DEFAULT 0,
    gate_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    summary JSONB NOT NULL DEFAULT '{}',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

ALTER TABLE eval_dataset ADD CONSTRAINT fk_eval_dataset_baseline FOREIGN KEY (baseline_run_id) REFERENCES eval_run(id) ON DELETE SET NULL;

CREATE TABLE eval_case_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    eval_run_id UUID NOT NULL REFERENCES eval_run(id) ON DELETE CASCADE,
    case_id UUID NOT NULL REFERENCES eval_case(id) ON DELETE RESTRICT,
    case_version BIGINT NOT NULL,
    repeat_index INTEGER NOT NULL,
    invocation_id VARCHAR(120) REFERENCES agent_invocation(id) ON DELETE SET NULL,
    session_id VARCHAR(120),
    status VARCHAR(32) NOT NULL,
    final_output TEXT,
    total_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    passed BOOLEAN NOT NULL DEFAULT false,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    tool_calls INTEGER NOT NULL DEFAULT 0,
    model_calls INTEGER NOT NULL DEFAULT 0,
    score_breakdown JSONB NOT NULL DEFAULT '{}',
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE(eval_run_id,case_id,repeat_index)
);

CREATE TABLE eval_assertion_result (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_run_id UUID NOT NULL REFERENCES eval_case_run(id) ON DELETE CASCADE,
    grader_type VARCHAR(60) NOT NULL,
    assertion_key VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    passed BOOLEAN NOT NULL,
    hard_gate BOOLEAN NOT NULL DEFAULT false,
    score DOUBLE PRECISION NOT NULL DEFAULT 0,
    max_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    expected JSONB NOT NULL DEFAULT '{}',
    actual JSONB NOT NULL DEFAULT '{}',
    details TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_eval_case_dataset ON eval_case(dataset_id,enabled);
CREATE INDEX idx_eval_run_owner_created ON eval_run(owner_username,created_at DESC);
CREATE INDEX idx_eval_case_run_run ON eval_case_run(eval_run_id,case_id,repeat_index);
CREATE INDEX idx_eval_case_run_invocation ON eval_case_run(invocation_id);
CREATE INDEX idx_eval_assertion_case_run ON eval_assertion_result(case_run_id);

INSERT INTO eval_dataset(owner_username,dataset_key,name,description)
VALUES ('admin','starter-regression','Agent 基础回归集','用于验证通用 Agent 和 Draw.io Agent 的基本输出、Tool 轨迹与预算。')
ON CONFLICT(owner_username,dataset_key) DO NOTHING;

INSERT INTO eval_case(dataset_id,case_key,name,agent_id,prompt,expectations,rubric,tags)
SELECT d.id,'general-direct-answer','简单问题不应滥用工具','300002',
       '请用一句话解释什么是幂等性。',
       '{"requiredText":["重复"],"forbiddenTools":["spawn_subagent","execute_capability"],"maxToolCalls":0,"maxModelCalls":2,"maxDurationMs":30000,"maxTokens":5000,"passScore":80}',
       '{"contentWeight":60,"trajectoryWeight":30,"efficiencyWeight":10}',
       '["general","tool-selection"]'
FROM eval_dataset d WHERE d.owner_username='admin' AND d.dataset_key='starter-regression'
ON CONFLICT(dataset_id,case_key) DO NOTHING;

INSERT INTO eval_case(dataset_id,case_key,name,agent_id,prompt,expectations,rubric,tags)
SELECT d.id,'drawio-login-flow','登录流程图应使用 Draw.io Skill','300000',
       '绘制账号密码登录流程图，包含参数校验、后端认证、登录成功和失败分支，主流程从上到下。',
       '{"requiredText":["drawio_done","mxGraphModel"],"requiredTools":["search_capabilities","load_capability","execute_capability"],"forbiddenTools":["spawn_subagent"],"maxToolCalls":8,"maxModelCalls":5,"maxDurationMs":120000,"maxTokens":30000,"passScore":75}',
       '{"contentWeight":50,"trajectoryWeight":40,"efficiencyWeight":10}',
       '["drawio","skill-selection","trajectory"]'
FROM eval_dataset d WHERE d.owner_username='admin' AND d.dataset_key='starter-regression'
ON CONFLICT(dataset_id,case_key) DO NOTHING;
