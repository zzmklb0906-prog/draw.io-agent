package cn.bugstack.ai.domain.identity.adapter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IWorkspaceRepository {
    List<Map<String,Object>> list(String username);
    Map<String,Object> create(String username,String name,String description);
    List<Map<String,Object>> members(String username,UUID workspaceId);
    void putMember(String username,UUID workspaceId,String memberUsername,String role);
    void removeMember(String username,UUID workspaceId,String memberUsername);
}

