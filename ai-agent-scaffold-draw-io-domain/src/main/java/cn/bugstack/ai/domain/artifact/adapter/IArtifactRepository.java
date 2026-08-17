package cn.bugstack.ai.domain.artifact.adapter;

import java.util.UUID;
import java.util.List;
import cn.bugstack.ai.domain.artifact.model.ArtifactView;

public interface IArtifactRepository {
    UUID save(UUID conversationId,String invocationId,String artifactType,String name,String mimeType,String content,String metadataJson);
    default UUID save(UUID conversationId,String invocationId,String artifactType,String name,String mimeType,String content,String metadataJson,String idempotencyKey){return save(conversationId,invocationId,artifactType,name,mimeType,content,metadataJson);}
    List<ArtifactView> list(String username,UUID conversationId);
    ArtifactView get(String username,UUID artifactId);
    ArtifactView rollback(String username,UUID artifactId,String invocationId,String idempotencyKey);
    ArtifactView branch(String username,UUID artifactId,String branchName,String invocationId,String idempotencyKey);
    String diff(String username,UUID baseId,UUID targetId);
}
