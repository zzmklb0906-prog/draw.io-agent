package cn.bugstack.ai.domain.agent.memory.service;

import cn.bugstack.ai.domain.agent.memory.adapter.repository.IMemoryRepository;
import cn.bugstack.ai.domain.agent.memory.model.entity.AgentMemoryEntity;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class MemoryConsolidationService {
    private final IMemoryRepository repository;
    private final MemoryService memories;
    public MemoryConsolidationService(IMemoryRepository repository,MemoryService memories){this.repository=repository;this.memories=memories;}

    /** 合并同类型、同作用域且规范化内容完全一致的候选；语义冲突保留给用户审核，不做猜测覆盖。 */
    public Map<String,Object> consolidate(String userId,String projectId){
        var all=repository.find(userId,projectId,null,500);Map<String,AgentMemoryEntity> canonical=new LinkedHashMap<>();int merged=0;
        for(AgentMemoryEntity candidate:all){String key=candidate.getMemoryType()+"\u0000"+normalize(candidate.getContent());AgentMemoryEntity keep=canonical.get(key);if(keep==null){canonical.put(key,candidate);continue;}if(better(candidate,keep)){AgentMemoryEntity old=keep;keep=candidate;canonical.put(key,candidate);candidate=old;}for(var evidence:repository.findEvidence(candidate.getMemoryId()))repository.addEvidence(keep.getMemoryId(),evidence.getEvidenceType(),evidence.getEvidenceId(),evidence.getCreatedAt());memories.delete(candidate.getMemoryId());merged++;}
        var conflicts=findConflicts(all);return Map.of("scanned",all.size(),"merged",merged,"remaining",all.size()-merged,"conflictCount",conflicts.size(),"conflicts",conflicts,"strategy","EXACT_NORMALIZED; structured conflicts require human review");
    }
    private boolean better(AgentMemoryEntity a,AgentMemoryEntity b){if(a.isConfirmed()!=b.isConfirmed())return a.isConfirmed();double as=a.getImportance()+a.getConfidence(),bs=b.getImportance()+b.getConfidence();return as==bs?a.getUpdatedAt()>b.getUpdatedAt():as>bs;}
    private String normalize(String value){return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+","").trim();}
    private java.util.List<Map<String,Object>> findConflicts(java.util.List<AgentMemoryEntity> all){java.util.List<Map<String,Object>> out=new java.util.ArrayList<>();for(int i=0;i<all.size()&&out.size()<50;i++)for(int j=i+1;j<all.size()&&out.size()<50;j++){AgentMemoryEntity a=all.get(i),b=all.get(j);if(!a.getMemoryType().equals(b.getMemoryType())||a.getStructuredData()==null||b.getStructuredData()==null)continue;try{var aj=com.alibaba.fastjson.JSON.parseObject(a.getStructuredData());var bj=com.alibaba.fastjson.JSON.parseObject(b.getStructuredData());for(String key:aj.keySet())if(bj.containsKey(key)&&!java.util.Objects.equals(aj.get(key),bj.get(key))){out.add(Map.of("memoryA",a.getMemoryId(),"memoryB",b.getMemoryId(),"field",key,"valueA",String.valueOf(aj.get(key)),"valueB",String.valueOf(bj.get(key))));break;}}catch(Exception ignored){}}return out;}
}
