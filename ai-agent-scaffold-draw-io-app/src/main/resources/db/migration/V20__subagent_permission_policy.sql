ALTER TABLE agent_template ADD COLUMN IF NOT EXISTS permission_mode VARCHAR(32) NOT NULL DEFAULT 'READ_ONLY'
    CHECK(permission_mode IN('READ_ONLY','APPROVAL_REQUIRED','WRITE_ALLOWED'));
UPDATE agent_template SET permission_mode='APPROVAL_REQUIRED' WHERE template_key='specialist';

