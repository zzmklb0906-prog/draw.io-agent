ALTER TABLE artifact ADD COLUMN IF NOT EXISTS lineage_id UUID;
ALTER TABLE artifact ADD COLUMN IF NOT EXISTS branch_name VARCHAR(120) NOT NULL DEFAULT 'main';
UPDATE artifact SET lineage_id=id WHERE lineage_id IS NULL;
ALTER TABLE artifact ALTER COLUMN lineage_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_artifact_lineage ON artifact(lineage_id,branch_name,version_no);
CREATE INDEX IF NOT EXISTS idx_artifact_conversation_versions ON artifact(conversation_id,artifact_type,created_at DESC);
