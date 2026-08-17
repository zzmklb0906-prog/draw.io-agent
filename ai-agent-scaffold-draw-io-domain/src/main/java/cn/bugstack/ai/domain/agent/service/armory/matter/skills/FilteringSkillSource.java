package cn.bugstack.ai.domain.agent.service.armory.matter.skills;

import com.google.adk.skills.Frontmatter;
import com.google.adk.skills.SkillSource;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import io.reactivex.rxjava3.core.Single;

import java.util.Map;
import java.util.Set;

/** Filters the catalog visible to one agent without eagerly loading skill bodies. */
public final class FilteringSkillSource implements SkillSource {
    private final SkillSource delegate;
    private final Set<String> included;

    public FilteringSkillSource(SkillSource delegate, Set<String> included) {
        this.delegate = delegate;
        this.included = Set.copyOf(included);
    }

    @Override
    public Single<ImmutableMap<String, Frontmatter>> listFrontmatters() {
        return delegate.listFrontmatters().map(all -> all.entrySet().stream()
                .filter(entry -> included.contains(entry.getKey()))
                .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    @Override public Single<ImmutableList<String>> listResources(String skillName, String path) { return allowed(skillName).flatMap(ignored -> delegate.listResources(skillName, path)); }
    @Override public Single<Frontmatter> loadFrontmatter(String skillName) { return allowed(skillName).flatMap(ignored -> delegate.loadFrontmatter(skillName)); }
    @Override public Single<String> loadInstructions(String skillName) { return allowed(skillName).flatMap(ignored -> delegate.loadInstructions(skillName)); }
    @Override public Single<ByteSource> loadResource(String skillName, String path) { return allowed(skillName).flatMap(ignored -> delegate.loadResource(skillName, path)); }

    private Single<String> allowed(String skillName) {
        return included.contains(skillName) ? Single.just(skillName)
                : Single.error(new IllegalArgumentException("Skill 不在当前工具组白名单中: " + skillName));
    }
}
