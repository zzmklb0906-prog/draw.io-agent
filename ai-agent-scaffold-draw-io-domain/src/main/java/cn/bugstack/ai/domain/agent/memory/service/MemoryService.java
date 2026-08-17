package cn.bugstack.ai.domain.agent.memory.service;

import cn.bugstack.ai.domain.agent.memory.adapter.repository.IMemoryRepository;
import cn.bugstack.ai.domain.agent.memory.model.entity.AgentMemoryEntity;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MemoryService {
    private static final Set<String> TYPES=Set.of("USER_PREFERENCE","PROJECT_FACT","EPISODE","PROCEDURE","TASK_LESSON");
    private final IMemoryRepository repository;
    public MemoryService(IMemoryRepository repository){this.repository=repository;}
    public AgentMemoryEntity create(AgentMemoryEntity input){if(input.getUserId()==null||input.getUserId().isBlank()||input.getContent()==null||input.getContent().isBlank())throw new AppException("MEMORY_INVALID","Memory 用户和内容不能为空");if(!TYPES.contains(input.getMemoryType()))throw new AppException("MEMORY_TYPE_INVALID","不支持的 Memory 类型");long now=System.currentTimeMillis();input.setMemoryId(UUID.randomUUID().toString());input.setImportance(clamp(input.getImportance()));input.setConfidence(clamp(input.getConfidence()));input.setCreatedAt(now);input.setUpdatedAt(now);input.setDeleted(false);return repository.insert(input);}
    public AgentMemoryEntity get(String id){return repository.findById(id).orElseThrow(()->new AppException("MEMORY_NOT_FOUND","Memory 不存在"));}
    public AgentMemoryEntity getOwned(String id,String user){AgentMemoryEntity memory=get(id);if(user==null||!user.equals(memory.getUserId()))throw new AppException("MEMORY_ACCESS_DENIED","无权访问该 Memory");return memory;}
    public List<AgentMemoryEntity> list(String user,String project,String type,int limit){return repository.find(user,project,type,Math.min(limit,200));}
    public List<AgentMemoryEntity> retrieve(String user,String project,String query,int limit){return repository.search(user,project,query,Math.min(limit,20));}
    public List<AgentMemoryEntity> retrieveConfirmed(String user,String project,String query,int limit){return repository.search(user,project,query,Math.min(limit,20)).stream().filter(AgentMemoryEntity::isConfirmed).toList();}
    public List<cn.bugstack.ai.domain.agent.memory.model.entity.MemoryEvidenceEntity> evidenceOwned(String id,String user){getOwned(id,user);return repository.findEvidence(id);}
    public void addEvidence(String id,String type,String evidenceId){if(evidenceId==null||evidenceId.isBlank())return;repository.addEvidence(id,type,evidenceId,System.currentTimeMillis());}
    public AgentMemoryEntity confirm(String id){AgentMemoryEntity m=get(id);long old=m.getUpdatedAt();m.setConfirmed(true);m.setUpdatedAt(System.currentTimeMillis());if(!repository.update(m,old))throw conflict();return m;}
    public AgentMemoryEntity update(String id,String content,String structuredData,double importance,boolean confirmed){AgentMemoryEntity m=get(id);long old=m.getUpdatedAt();if(content!=null&&!content.isBlank())m.setContent(content);m.setStructuredData(structuredData);m.setImportance(clamp(importance));m.setConfirmed(confirmed);m.setUpdatedAt(System.currentTimeMillis());if(!repository.update(m,old))throw conflict();return m;}
    public void delete(String id){AgentMemoryEntity m=get(id);long old=m.getUpdatedAt();m.setDeleted(true);m.setUpdatedAt(System.currentTimeMillis());if(!repository.update(m,old))throw conflict();}
    public AgentMemoryEntity confirmOwned(String id,String user){getOwned(id,user);return confirm(id);}
    public AgentMemoryEntity updateOwned(String id,String user,String content,String structuredData,double importance,boolean confirmed){getOwned(id,user);return update(id,content,structuredData,importance,confirmed);}
    public void deleteOwned(String id,String user){getOwned(id,user);delete(id);}
    private double clamp(double v){return Math.max(0,Math.min(v,1));}private AppException conflict(){return new AppException("MEMORY_CONFLICT","Memory 已变化，请刷新后重试");}
}
