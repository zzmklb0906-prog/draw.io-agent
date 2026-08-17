package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.armory.matter.tool.GovernedToolset;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GovernedToolsetTest {

    @Test
    void shouldOpenCircuitAfterConsecutiveFailures() {
        AtomicInteger calls = new AtomicInteger();
        BaseTool failing = new BaseTool("unstable", "always fails") {
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
                calls.incrementAndGet();
                return Single.error(new IllegalStateException("remote failed"));
            }
        };
        BaseToolset source = new BaseToolset() {
            @Override public Flowable<BaseTool> getTools(ReadonlyContext context) { return Flowable.just(failing); }
            @Override public void close() { }
        };
        BaseTool governed = new GovernedToolset(source, 5_000, 2, 60_000).getTools(null).blockingFirst();

        governed.runAsync(Map.of(), null).test().assertError(IllegalStateException.class);
        governed.runAsync(Map.of(), null).test().assertError(IllegalStateException.class);
        governed.runAsync(Map.of(), null).test().assertError(error ->
                error instanceof IllegalStateException && error.getMessage().contains("熔断"));

        assertEquals(2, calls.get());
    }

    @Test
    void shouldIsolateExecuteCapabilityCircuitsByCapabilityId() {
        AtomicInteger calls = new AtomicInteger();
        BaseTool failing = new BaseTool("execute_capability", "multiplexed capability executor") {
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
                calls.incrementAndGet();
                return Single.error(new IllegalStateException("remote failed: "+args.get("capabilityId")));
            }
        };
        BaseToolset source = new BaseToolset() {
            @Override public Flowable<BaseTool> getTools(ReadonlyContext context) { return Flowable.just(failing); }
            @Override public void close() { }
        };
        BaseTool governed = new GovernedToolset(source,5_000,1,60_000).getTools(null).blockingFirst();
        governed.runAsync(Map.of("capabilityId","mcp:a"),null).test().assertError(error->error.getMessage().contains("remote failed"));
        governed.runAsync(Map.of("capabilityId","mcp:b"),null).test().assertError(error->error.getMessage().contains("remote failed"));
        assertEquals(2,calls.get());
    }
}
