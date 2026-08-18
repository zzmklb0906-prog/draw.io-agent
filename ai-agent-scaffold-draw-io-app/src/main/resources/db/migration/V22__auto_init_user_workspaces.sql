-- V22: 自动为所有已存在用户补全默认 Agent Workspace 与成员权限映射
INSERT INTO agent_workspace (id, owner_user_id, name, description)
SELECT gen_random_uuid(), u.id, u.username || ' 的个人工作区', '系统自动初始化的默认工作区'
FROM app_user u
WHERE NOT EXISTS (
    SELECT 1 FROM agent_workspace w WHERE w.owner_user_id = u.id
)
ON CONFLICT DO NOTHING;

INSERT INTO workspace_member (workspace_id, user_id, role)
SELECT w.id, w.owner_user_id, 'OWNER'
FROM agent_workspace w
ON CONFLICT (workspace_id, user_id) DO NOTHING;
