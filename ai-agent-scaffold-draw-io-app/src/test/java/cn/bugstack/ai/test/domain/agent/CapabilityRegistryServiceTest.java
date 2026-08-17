package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.capability.CapabilityRegistryService;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityRegistryServiceTest {

    @Test
    void shouldSearchLoadAndExecuteOnlySnapshotCapabilities() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", java.util.List.of("code"));
        registry.registerTool("code", "JAVA_TOOL", "READ_ONLY", new BaseTool("search_code", "Search Java source code and symbols") {
            @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args, ToolContext context) {
                return Single.just(Map.of("query", args.get("query"), "matches", 3));
            }
        });
        registry.registerTool("ops", "JAVA_TOOL", "READ_ONLY", new BaseTool("restart_server", "Restart a server") {
            @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args, ToolContext context) { return Single.just(Map.of()); }
        });

        var result = registry.search("inv-1", "admin", "general", "search Java code", java.util.List.of("JAVA_TOOL"), 8);
        assertEquals(1, result.capabilities().size());
        String capabilityId = result.capabilities().get(0).capability().capabilityId();
        assertEquals("search_code", registry.load(result.snapshotId(), capabilityId, null).name());
        assertEquals(3, registry.execute(result.snapshotId(), capabilityId, Map.of("query", "Checkpoint"), null).blockingGet().get("matches"));
        assertThrows(SecurityException.class, () -> registry.load(result.snapshotId(), "java_tool:ops:restart_server", null));
    }

    @Test
    void shouldLimitSearchResultsAndRestoreSnapshot() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.allowAgentGroups("general", java.util.List.of("*"));
        for (int i=0;i<30;i++) {
            int index=i;
            registry.registerTool("group", "JAVA_TOOL", "READ_ONLY", new BaseTool("reader_"+i, "Read project file number "+i) {
                @Override public Single<Map<String,Object>> runAsync(Map<String,Object> args, ToolContext context) { return Single.just(Map.of("index",index)); }
            });
        }
        var result=registry.search("inv","admin","general","read project file",java.util.List.of(),100);
        assertEquals(16,result.capabilities().size());
        var ids=result.capabilities().stream().map(item->item.capability().capabilityId()).toList();
        registry.restoreSnapshot("restored","inv-2","admin","general",ids);
        assertEquals(ids.size(),registry.snapshotCapabilities("restored").size());
    }
}
