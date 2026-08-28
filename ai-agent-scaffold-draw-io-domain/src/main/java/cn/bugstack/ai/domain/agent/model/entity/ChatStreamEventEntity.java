package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEventEntity {

    private Long id;
    private String runId;
    private Long sequenceNo;
    private String eventType;
    private String phase;
    private String dataJson;
    private Date createdAt;

}
