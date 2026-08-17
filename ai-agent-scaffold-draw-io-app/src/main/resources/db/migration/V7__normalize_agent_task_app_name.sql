-- Workflow represents the configured Agent application, not the concrete
-- checkpoint stage agent (agent_analyst / agent_drawer).
UPDATE agent_task t
SET title = CASE c.agent_id
    WHEN '300000' THEN 'drawIoAgent'
    WHEN '300001' THEN 'pptAgent'
    WHEN '300002' THEN 'generalAgent'
    ELSE t.title
END,
updated_at = now(),
version = t.version + 1
FROM conversation c
WHERE c.id = t.conversation_id
  AND t.title <> CASE c.agent_id
      WHEN '300000' THEN 'drawIoAgent'
      WHEN '300001' THEN 'pptAgent'
      WHEN '300002' THEN 'generalAgent'
      ELSE t.title
  END;
