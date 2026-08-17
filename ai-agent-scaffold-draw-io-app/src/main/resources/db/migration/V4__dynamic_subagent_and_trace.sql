ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS branch_path VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS run_kind VARCHAR(30) NOT NULL DEFAULT 'STATIC';
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS template_key VARCHAR(120);

CREATE TABLE agent_template (
    template_key VARCHAR(120) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    instruction TEXT NOT NULL,
    capability_groups JSONB NOT NULL DEFAULT '[]',
    max_steps INTEGER NOT NULL DEFAULT 12,
    timeout_seconds INTEGER NOT NULL DEFAULT 120,
    enabled BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dynamic_subagent_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_invocation_id VARCHAR(120) NOT NULL REFERENCES agent_invocation(id) ON DELETE CASCADE,
    parent_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
    child_run_id UUID REFERENCES agent_run(id) ON DELETE SET NULL,
    template_key VARCHAR(120) NOT NULL REFERENCES agent_template(template_key),
    requested_by VARCHAR(120) NOT NULL,
    task_text TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_text TEXT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_dynamic_subagent_invocation ON dynamic_subagent_task(parent_invocation_id,created_at);
CREATE INDEX idx_dynamic_subagent_status ON dynamic_subagent_task(status,created_at);

INSERT INTO agent_template(template_key,display_name,description,instruction,capability_groups,max_steps,timeout_seconds)
VALUES
('researcher','研究分析 Agent','对复杂问题进行只读检索、证据整理和结构化分析。','你是受控的研究分析 Subagent。只完成委派任务，不扩大范围。优先给出结论、证据、风险和未确认项；不得声称执行未实际执行的操作。','["general-skills"]',12,120),
('reviewer','质量审查 Agent','独立审查方案、代码或文档，发现缺陷并给出可执行建议。','你是独立质量审查 Subagent。检查任务结果的正确性、完整性、安全性和可维护性。输出按严重程度排序的问题、证据和修复建议。','[]',10,90),
('specialist','通用专业 Agent','执行边界清晰的专业子任务，并返回可合并的结果。','你是受控的专业 Subagent。严格围绕委派任务工作，交付可直接合并的结果、关键假设、验证情况与剩余风险。','["general-skills"]',16,180)
ON CONFLICT (template_key) DO NOTHING;
