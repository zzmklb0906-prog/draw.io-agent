package cn.bugstack.ai.domain.agent.service.armory.matter.skills.impl;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import cn.bugstack.ai.domain.agent.service.armory.matter.skills.FilteringSkillSource;
import cn.bugstack.ai.domain.agent.service.capability.CapabilityRegistryService;
import com.google.adk.tools.BaseTool;
import com.google.adk.skills.LocalSkillSource;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.skills.SkillToolset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 使用 Google ADK 1.7 原生 SkillSource/SkillToolset 暴露 skills。
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/2/6 08:04
 */
@Slf4j
@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {
    private final CapabilityRegistryService capabilityRegistry;

    public DefaultToolSkillsCreateService(CapabilityRegistryService capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    @Override
    public Object buildToolset(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception {

        String type = toolSkills.getType();
        String path = toolSkills.getPath();

        SkillSource source;
        if ("directory".equals(type)) source = new LocalSkillSource(Path.of(path).toAbsolutePath().normalize());
        else if ("resource".equals(type)) source = resourceSkillSource(path);
        else throw new IllegalArgumentException("Unsupported skill source type: " + type);

        List<String> included = toolSkills.getIncludedSkills() == null ? List.of() : toolSkills.getIncludedSkills().stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (included.size() > 32) throw new IllegalArgumentException("单个 Skill 工具组最多暴露 32 个 Skill，请按领域拆组");
        if (!included.isEmpty()) source = new FilteringSkillSource(source, new LinkedHashSet<>(included));
        SkillToolset toolset = new SkillToolset(source);
        Map<String, BaseTool> tools = toolset.getTools(null).toMap(BaseTool::name).blockingGet();
        BaseTool loadSkill = tools.get("load_skill");
        BaseTool loadResource = tools.get("load_skill_resource");
        if (loadSkill == null) throw new IllegalStateException("ADK SkillToolset 缺少 load_skill");
        var frontmatters=source.listFrontmatters().blockingGet();
        frontmatters.values().forEach(frontmatter ->
                capabilityRegistry.registerSkill(toolSkills.getName(), frontmatter, loadSkill, loadResource));
        log.info("Skill 能力组 '{}' 注册 {} 项能力，来源 {}",toolSkills.getName(),frontmatters.size(),path);
        return toolset;
    }

    private SkillSource resourceSkillSource(String path) throws Exception {
        ClassPathResource root=new ClassPathResource(path);
        if("file".equalsIgnoreCase(root.getURL().getProtocol()))return new LocalSkillSource(root.getFile().toPath());
        Path temporary=Files.createTempDirectory("agent-skills-");
        var resolver=new PathMatchingResourcePatternResolver();
        for(var resource:resolver.getResources("classpath*:"+path+"/**")){
            if(!resource.isReadable())continue;
            String url=resource.getURL().toString();int marker=url.indexOf(path+"/");
            if(marker<0)continue;
            String relative=url.substring(marker+path.length()+1);
            int nested=relative.indexOf("!/");if(nested>=0)relative=relative.substring(nested+2);
            Path destination=temporary.resolve(relative).normalize();
            if(!destination.startsWith(temporary))throw new IllegalArgumentException("非法 Skill 资源路径: "+relative);
            if(destination.getParent()!=null)Files.createDirectories(destination.getParent());
            try(var input=resource.getInputStream()){Files.copy(input,destination,StandardCopyOption.REPLACE_EXISTING);}
        }
        log.info("已从 JAR 提取 Skill 资源 {} 到 {}",path,temporary);
        return new LocalSkillSource(temporary);
    }

}
