package cn.bugstack.ai.domain.agent.service.armory.matter.tool;

import cn.bugstack.ai.domain.idempotency.service.IdempotencyService;
import cn.bugstack.ai.domain.agent.service.monitor.LightweightMonitorService;
import com.alibaba.fastjson.JSON;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime guardrail for dynamically discovered MCP/Skill tools. */
public final class GovernedToolset implements BaseToolset {
    private final BaseToolset delegate;
    private final long timeoutMs;
    private final int failureThreshold;
    private final long cooldownMs;
    private final int maxRetries;
    private final long retryBackoffMs;
    private final int maxResultBytes;
    private final IdempotencyService idempotency;
    private final LightweightMonitorService monitor;
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> concurrency = new ConcurrentHashMap<>();

    public GovernedToolset(BaseToolset delegate, long timeoutMs, int failureThreshold, long cooldownMs) {
        this(delegate, timeoutMs, failureThreshold, cooldownMs, 0, 1000L, 10, 524_288, null, null);
    }

    public GovernedToolset(BaseToolset delegate, long timeoutMs, int failureThreshold, long cooldownMs,
                           int maxRetries, long retryBackoffMs, int maxConcurrency, int maxResultBytes,
                           IdempotencyService idempotency,LightweightMonitorService monitor) {
        this.delegate = delegate;
        this.timeoutMs = Math.max(1_000, timeoutMs);
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMs = Math.max(1_000, cooldownMs);
        this.maxRetries = Math.max(0, maxRetries);
        this.retryBackoffMs = Math.max(100, retryBackoffMs);
        this.maxResultBytes = Math.max(4_096, maxResultBytes);
        this.idempotency = idempotency;
        this.monitor = monitor;
        this.defaultConcurrency = Math.max(1, maxConcurrency);
    }
    private final int defaultConcurrency;

    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext context) {
        return delegate.getTools(context).map(tool -> new GovernedTool(tool));
    }

    @Override public Completable processLlmRequest(LlmRequest.Builder request, ToolContext context) { return delegate.processLlmRequest(request, context); }
    @Override public boolean isToolSelected(BaseTool tool, Object readonlyContext, ReadonlyContext context) {
        BaseTool original = tool instanceof GovernedTool governed ? governed.target : tool;
        return delegate.isToolSelected(original, readonlyContext, context);
    }
    @Override public void close() throws Exception { delegate.close(); }

    private final class GovernedTool extends BaseTool {
        private final BaseTool target;

        private GovernedTool(BaseTool target) {
            super(target.name(), target.description(), target.longRunning());
            this.target = target;
            target.customMetadata().forEach(this::setCustomMetadata);
            if(!customMetadata().containsKey("timeoutMs"))setCustomMetadata("timeoutMs",timeoutMs);
            if(!customMetadata().containsKey("maxRetries"))setCustomMetadata("maxRetries",maxRetries);
            if(!customMetadata().containsKey("maxConcurrency"))setCustomMetadata("maxConcurrency",defaultConcurrency);
            if(!customMetadata().containsKey("maxResultBytes"))setCustomMetadata("maxResultBytes",maxResultBytes);
            if(!customMetadata().containsKey("idempotent"))setCustomMetadata("idempotent",!Set.of("execute_capability","spawn_subagent").contains(target.name()));
            if(!customMetadata().containsKey("parallelAllowed"))setCustomMetadata("parallelAllowed",true);
        }

        @Override public Optional<FunctionDeclaration> declaration() { return target.declaration(); }
        @Override public Completable processLlmRequest(LlmRequest.Builder request, ToolContext context) { return target.processLlmRequest(request, context); }

        @Override
        public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
            // execute_capability multiplexes many real tools. Isolate its circuit by capability
            // so one failing MCP server cannot disable every other runtime capability.
            String circuitKey = name() + (args != null && args.get("capabilityId") != null ? ":" + args.get("capabilityId") : "");
            boolean defaultIdempotent=!Set.of("execute_capability","spawn_subagent").contains(name());
            boolean idempotent=customMetadata().get("idempotent") instanceof Boolean declared?declared:defaultIdempotent;
            long effectiveTimeout=number(customMetadata().get("timeoutMs"),timeoutMs);
            int effectiveRetries=(int)number(customMetadata().get("maxRetries"),maxRetries);
            int effectiveConcurrency=Boolean.FALSE.equals(customMetadata().get("parallelAllowed"))?1:(int)number(customMetadata().get("maxConcurrency"),defaultConcurrency);
            CircuitState circuit = circuits.computeIfAbsent(circuitKey, ignored -> new CircuitState());
            long now = System.currentTimeMillis();
            if (circuit.openUntil > now) {
                return Single.error(new IllegalStateException("Tool 熔断中，请在 " + (circuit.openUntil - now) + "ms 后重试: " + circuitKey));
            }
            String owner=context==null?"system":context.userId();
            String invocation=context==null?"unknown":context.invocationId();
            String callId=context==null?"":context.functionCallId().orElse("");
            String requestId=context==null?"":Objects.toString(context.invocationContext().runConfig().customMetadata().get("platformRequestId"),"");
            String operationIdentity=requestId.isBlank()?invocation+":"+callId:requestId;
            String key=callId.isBlank()?null:operationIdentity+":"+circuitKey+":"+Integer.toHexString(Objects.hashCode(args));
            IdempotencyService.Claim claim=idempotency.begin(owner,"TOOL_EXECUTION:"+name(),key,JSON.toJSONString(args));
            if(claim.replay()){
                Map<String,Object> cached=JSON.parseObject(claim.record().responseJson(),Map.class);
                return Single.just(cached==null?Map.of():cached);
            }
            Semaphore permits=concurrency.computeIfAbsent(circuitKey,ignored->new Semaphore(Math.max(1,effectiveConcurrency),true));
            AtomicInteger retryNo=new AtomicInteger();
            return Single.defer(()->{
                        if(!permits.tryAcquire())return Single.error(new IllegalStateException("Tool 并发已达上限: "+circuitKey));
                        return target.runAsync(args, context).doFinally(permits::release);
                    })
                    .timeout(Math.max(1_000,effectiveTimeout), TimeUnit.MILLISECONDS)
                    .retryWhen(errors->errors.flatMap(error->{
                        int attempt=retryNo.incrementAndGet();
                        if(!idempotent||attempt>effectiveRetries)return Flowable.error(error);
                        monitor.toolRetry(invocation,callId,attempt+1,error);
                        long delay=Math.min(retryBackoffMs*(1L<<(attempt-1)),30_000L);
                        return Flowable.timer(delay,TimeUnit.MILLISECONDS);
                    }))
                    .doOnSuccess(result -> {
                        circuit.failures.set(0); circuit.openUntil = 0;
                        idempotency.complete(owner,"TOOL_EXECUTION:"+name(),key,key,JSON.toJSONString(result));
                    })
                    .doOnError(error -> {
                        idempotency.fail(owner,"TOOL_EXECUTION:"+name(),key,error);
                        if (circuit.failures.incrementAndGet() >= failureThreshold) circuit.openUntil = System.currentTimeMillis() + cooldownMs;
                    });
        }

        private long number(Object value,long fallback){return value instanceof Number n?n.longValue():fallback;}

    }

    private static final class CircuitState {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openUntil;
    }
}
