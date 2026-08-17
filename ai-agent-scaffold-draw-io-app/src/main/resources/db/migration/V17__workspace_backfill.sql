INSERT INTO agent_workspace(owner_user_id,name,description)
SELECT u.id, u.display_name || ' 的工作区', '系统自动创建的个人工作区'
FROM app_user u
WHERE NOT EXISTS (SELECT 1 FROM agent_workspace w WHERE w.owner_user_id=u.id);

INSERT INTO workspace_member(workspace_id,user_id,role)
SELECT w.id,w.owner_user_id,'OWNER' FROM agent_workspace w
ON CONFLICT(workspace_id,user_id) DO UPDATE SET role='OWNER';

UPDATE conversation c SET workspace_id=(
    SELECT w.id FROM agent_workspace w WHERE w.owner_user_id=c.user_id ORDER BY w.created_at LIMIT 1
) WHERE c.workspace_id IS NULL;

UPDATE artifact a SET workspace_id=c.workspace_id
FROM conversation c WHERE a.conversation_id=c.id AND a.workspace_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_conversation_workspace_updated
    ON conversation(workspace_id,updated_at DESC) WHERE deleted_at IS NULL;

