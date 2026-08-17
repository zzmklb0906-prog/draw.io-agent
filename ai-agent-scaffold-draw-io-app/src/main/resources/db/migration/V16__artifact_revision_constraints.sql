CREATE UNIQUE INDEX IF NOT EXISTS uk_artifact_lineage_branch_version
    ON artifact(lineage_id, branch_name, version_no);

