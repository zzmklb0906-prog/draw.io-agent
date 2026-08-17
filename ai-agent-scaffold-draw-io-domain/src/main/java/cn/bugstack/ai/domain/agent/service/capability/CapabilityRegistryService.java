package cn.bugstack.ai.domain.agent.service.capability;

import cn.bugstack.ai.domain.agent.model.valobj.CapabilityDescriptor;
import com.google.adk.skills.Frontmatter;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import io.reactivex.rxjava3.core.Single;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Hot capability registry. Agents only receive broker meta-tools; real schemas stay here until selected. */
@Service
public class CapabilityRegistryService {
    private static final int MAX_SNAPSHOTS = 500;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> agentGroups = new ConcurrentHashMap<>();
    private final Map<String, String> agentRiskPolicies = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Deque<String> snapshotOrder = new ArrayDeque<>();

    public void allowAgentGroups(String agentName, Collection<String> groups) {
        agentGroups.put(agentName, groups == null ? Set.of() : Set.copyOf(groups));
    }
    public void allowAgentPolicy(String agentName,Collection<String> groups,String permissionMode){allowAgentGroups(agentName,groups);agentRiskPolicies.put(agentName,Objects.toString(permissionMode,"READ_ONLY"));}
    public void removeAgentPolicy(String agentName){agentGroups.remove(agentName);agentRiskPolicies.remove(agentName);}

    public void registerToolset(String group, String type, String riskLevel, BaseToolset toolset) {
        for (BaseTool tool : toolset.getTools(null).blockingIterable()) registerTool(group, type, riskLevel, tool);
    }

    public void registerTool(String group, String type, String riskLevel, BaseTool tool) {
        String id = normalize(type) + ":" + normalize(group) + ":" + normalize(tool.name());
        Map<String,Object> schema = tool.declaration().map(declaration -> {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("functionDeclaration", declaration.toJson());
            return result;
        }).orElseGet(Map::of);
        CapabilityDescriptor descriptor = new CapabilityDescriptor(id, type, group, tool.name(), tool.description(),
                tokenize(group + " " + tool.name() + " " + tool.description()).stream().limit(12).toList(),
                riskLevel == null || riskLevel.isBlank() ? "REQUIRES_APPROVAL" : riskLevel.toUpperCase(), schema, 1,
                fingerprint(JSON(schema)),fingerprint(tool.name()+"\n"+tool.description()));
        entries.put(id, new Entry(descriptor, (args, context) -> tool.runAsync(args, context)));
    }

    public void registerSkill(String group, Frontmatter skill, BaseTool loadSkill, BaseTool loadResource) {
        String id = "skill:" + normalize(group) + ":" + normalize(skill.name());
        CapabilityDescriptor descriptor = new CapabilityDescriptor(id, "SKILL", group, skill.name(), skill.description(),
                tokenize(skill.name() + " " + skill.description()).stream().limit(12).toList(), "READ_ONLY",
                Map.of("skillName", skill.name(), "supportsResource", loadResource != null), 1,
                fingerprint("skillName,supportsResource"),fingerprint(skill.name()+"\n"+skill.description()));
        entries.put(id, new Entry(descriptor, (args, context) -> {
            String resourcePath = Objects.toString(args.get("resourcePath"), "").trim();
            if (!resourcePath.isEmpty()) {
                if (loadResource == null) return Single.error(new IllegalArgumentException("Skill 不支持资源读取"));
                return loadResource.runAsync(Map.of("skill_name", skill.name(), "file_path", resourcePath), context);
            }
            return loadSkill.runAsync(Map.of("skill_name", skill.name()), context);
        }));
    }

    public synchronized SearchResult search(String invocationId, String userId, String agentName, String query,
                                            Collection<String> requestedTypes, int requestedLimit) {
        final String normalizedQuery = query == null ? "" : query;
        int limit = Math.max(1, Math.min(16, requestedLimit));
        Set<String> allowed = agentGroups.getOrDefault(agentName, Set.of());
        Set<String> types = requestedTypes == null ? Set.of() : requestedTypes.stream().map(String::toUpperCase).collect(java.util.stream.Collectors.toSet());
        Set<String> queryTokens = tokenize(normalizedQuery);
        List<ScoredCapability> matches = entries.values().stream()
                .filter(entry -> allowed.contains("*") || allowed.contains(entry.descriptor.group()))
                .filter(entry -> riskRank(entry.descriptor.riskLevel())<=permissionRank(agentRiskPolicies.get(agentName)))
                .filter(entry -> types.isEmpty() || types.contains(entry.descriptor.type().toUpperCase()))
                .map(entry -> new ScoredCapability(entry.descriptor, score(normalizedQuery, queryTokens, entry.descriptor)))
                .filter(item -> item.score() > 0 || queryTokens.isEmpty())
                .sorted(Comparator.comparingDouble(ScoredCapability::score).reversed().thenComparing(item -> item.capability().capabilityId()))
                .limit(limit).toList();
        String snapshotId = UUID.randomUUID().toString();
        snapshots.put(snapshotId, new Snapshot(snapshotId, invocationId, userId, agentName,
                matches.stream().map(item -> item.capability().capabilityId()).collect(java.util.stream.Collectors.toUnmodifiableSet()), System.currentTimeMillis()));
        snapshotOrder.addFirst(snapshotId);
        while (snapshotOrder.size() > MAX_SNAPSHOTS) snapshots.remove(snapshotOrder.removeLast());
        return new SearchResult(snapshotId, matches, entries.size());
    }

    public CapabilityDescriptor load(String snapshotId, String capabilityId, ToolContext context) {
        validate(snapshotId, capabilityId, context);
        return require(capabilityId).descriptor;
    }

    public Set<String> snapshotCapabilities(String snapshotId) {
        Snapshot snapshot = snapshots.get(snapshotId);
        return snapshot == null ? Set.of() : snapshot.capabilityIds;
    }

    public void restoreSnapshot(String snapshotId, String invocationId, String userId, String agentName, Collection<String> capabilityIds) {
        if (snapshotId == null || snapshotId.isBlank() || capabilityIds == null) return;
        Set<String> existing = capabilityIds.stream().filter(entries::containsKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
        snapshots.putIfAbsent(snapshotId, new Snapshot(snapshotId, invocationId, userId, agentName, existing, System.currentTimeMillis()));
    }

    public Single<Map<String,Object>> execute(String snapshotId, String capabilityId, Map<String,Object> arguments, ToolContext context) {
        validate(snapshotId, capabilityId, context);
        return require(capabilityId).executor.execute(arguments == null ? Map.of() : arguments, context);
    }

    public int size() { return entries.size(); }

    private void validate(String snapshotId, String capabilityId, ToolContext context) {
        Snapshot snapshot = snapshots.get(snapshotId);
        if (snapshot == null) throw new IllegalArgumentException("能力快照不存在或已过期，请重新搜索");
        if (context != null && (!snapshot.userId.equals(context.userId()) || !snapshot.agentName.equals(context.agentName())))
            throw new SecurityException("能力快照不属于当前用户或 Agent");
        if (!snapshot.capabilityIds.contains(capabilityId)) throw new SecurityException("能力不在当前检索快照中");
        if(context!=null&&riskRank(require(capabilityId).descriptor.riskLevel())>permissionRank(agentRiskPolicies.get(context.agentName())))throw new SecurityException("Agent 权限策略不允许执行该能力");
    }

    private Entry require(String id) { Entry entry = entries.get(id); if (entry == null) throw new IllegalArgumentException("能力不存在: " + id); return entry; }
    private double score(String query, Set<String> queryTokens, CapabilityDescriptor d) {
        String haystack = (d.name() + " " + d.description() + " " + d.group() + " " + String.join(" ", d.tags())).toLowerCase();
        double score = haystack.contains(query.toLowerCase()) && !query.isBlank() ? 8 : 0;
        for (String token : queryTokens) if (haystack.contains(token)) score += token.length() > 1 ? 2 : .35;
        if (d.name().equalsIgnoreCase(query.trim())) score += 12;
        return score;
    }
    private static Set<String> tokenize(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String normalized = value.toLowerCase().replaceAll("[^\\p{L}\\p{N}_-]+", " ").trim();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String word : normalized.split("\\s+")) {
            if (!word.isBlank()) tokens.add(word);
            if (word.length() >= 4) for (int i=0;i<word.length()-1;i++) tokens.add(word.substring(i,i+2));
        }
        return tokens;
    }
    private static String normalize(String value) { return value.toLowerCase().replaceAll("[^a-z0-9_-]+", "-"); }
    private static int permissionRank(String mode){if(mode==null||mode.isBlank())return 2;return switch(mode.toUpperCase(Locale.ROOT)){case "READ_ONLY"->0;case "APPROVAL_REQUIRED"->1;default->2;};}
    private static int riskRank(String risk){if(risk==null||risk.isBlank())return 1;return switch(risk.toUpperCase(Locale.ROOT)){case "READ_ONLY","LOW"->0;case "REQUIRES_APPROVAL","MEDIUM"->1;default->2;};}
    private static String JSON(Object value){return com.alibaba.fastjson.JSON.toJSONString(value);}
    private static String fingerprint(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,16);}catch(Exception e){throw new IllegalStateException(e);}}

    private record Entry(CapabilityDescriptor descriptor, Executor executor) { }
    @FunctionalInterface private interface Executor { Single<Map<String,Object>> execute(Map<String,Object> args, ToolContext context); }
    private record Snapshot(String id, String invocationId, String userId, String agentName, Set<String> capabilityIds, long createdAt) { }
    public record ScoredCapability(CapabilityDescriptor capability, double score) { }
    public record SearchResult(String snapshotId, List<ScoredCapability> capabilities, int registrySize) { }
}
