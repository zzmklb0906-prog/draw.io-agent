package cn.bugstack.ai.domain.artifact.model;

import java.time.Instant;
import java.util.UUID;

public record ArtifactView(UUID id,UUID conversationId,String invocationId,UUID parentArtifactId,UUID lineageId,
                           String branchName,String artifactType,String name,String mimeType,String content,
                           String contentHash,long sizeBytes,int versionNo,String status,Instant createdAt) {}
