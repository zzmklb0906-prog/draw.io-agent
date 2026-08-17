package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IWorkflowCheckpointRepository;
import cn.bugstack.ai.domain.agent.model.entity.WorkflowCheckpointEntity;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name="ai.agent.persistence.mode",havingValue="file")
public class FileWorkflowCheckpointRepository implements IWorkflowCheckpointRepository {
    private final Path root;

    public FileWorkflowCheckpointRepository(@Value("${ai.agent.persistence.root:${user.dir}/data/agent-runtime}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize().resolve("checkpoints");
    }

    @Override
    public synchronized WorkflowCheckpointEntity save(WorkflowCheckpointEntity checkpoint) {
        try {
            Files.createDirectories(root);
            Path target = file(checkpoint.getCheckpointId());
            Path temporary = Files.createTempFile(root, checkpoint.getCheckpointId(), ".tmp");
            Files.writeString(temporary, JSON.toJSONString(checkpoint), StandardCharsets.UTF_8);
            move(temporary, target);
            return checkpoint;
        } catch (IOException e) {
            throw new IllegalStateException("持久化 Checkpoint 失败", e);
        }
    }

    @Override
    public synchronized Optional<WorkflowCheckpointEntity> findById(String checkpointId) {
        Path path = file(checkpointId);
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8), WorkflowCheckpointEntity.class));
        } catch (IOException e) {
            throw new IllegalStateException("读取 Checkpoint 失败", e);
        }
    }

    private Path file(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("非法 Checkpoint ID");
        return root.resolve(id + ".json");
    }

    private void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
