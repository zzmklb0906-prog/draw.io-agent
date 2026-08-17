package cn.bugstack.ai.domain.agent.memory.adapter.repository;

import cn.bugstack.ai.domain.agent.memory.model.entity.AgentMemoryEntity;
import cn.bugstack.ai.domain.agent.memory.model.entity.MemoryEvidenceEntity;
import java.util.List;
import java.util.Optional;

public interface IMemoryRepository {
    AgentMemoryEntity insert(AgentMemoryEntity memory);
    Optional<AgentMemoryEntity> findById(String id);
    List<AgentMemoryEntity> find(String userId,String projectId,String type,int limit);
    boolean update(AgentMemoryEntity memory,long expectedUpdatedAt);
    List<AgentMemoryEntity> search(String userId,String projectId,String query,int limit);
    List<MemoryEvidenceEntity> findEvidence(String memoryId);
    void addEvidence(String memoryId,String evidenceType,String evidenceId,long createdAt);
}
