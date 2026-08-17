package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.WorkflowCheckpointEntity;

import java.util.Optional;

public interface IWorkflowCheckpointRepository {
    WorkflowCheckpointEntity save(WorkflowCheckpointEntity checkpoint);
    Optional<WorkflowCheckpointEntity> findById(String checkpointId);
}
