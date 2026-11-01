package com.yanque.aimedia.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 处理任务中的一个可独立记录、独立重试的步骤。 */
@Data
public class AiMediaProcessStepEntity {
    /** 步骤主键。 */
    private Long id;
    /** 所属处理任务 ID。 */
    private Long taskId;
    /** PREPARE_VIDEO、EXTRACT_AUDIO、ASR_SUBMIT、ASR_QUERY、TRANSCRIPT_REFINEMENT 等节点编码。 */
    private String stepCode;
    /** 节点执行顺序，数值越小越先执行。 */
    private Integer stepOrder;
    /** PENDING、PROCESSING、WAITING、SUCCESS 或 FAILED。 */
    private String status;
    /** 重试次数：人工点重试时自增；ASR 查询遇瞬时故障退避时也按它计算下一次可查询时间。 */
    private Integer retryCount;
    /** 当前节点最后一次失败的错误原因。 */
    private String errorMessage;
    /** 节点开始执行时间。 */
    private LocalDateTime startedAt;
    /** 节点成功或失败时间。 */
    private LocalDateTime finishedAt;
    /** 数据创建时间。 */
    private LocalDateTime createdAt;
    /** 数据最后更新时间。 */
    private LocalDateTime updatedAt;
}
