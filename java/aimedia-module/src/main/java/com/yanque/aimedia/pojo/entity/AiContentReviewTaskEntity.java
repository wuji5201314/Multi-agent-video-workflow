package com.yanque.aimedia.pojo.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 一篇文章一个版本对应的一次审核任务。 */
@Data
public class AiContentReviewTaskEntity {
    private Long id;
    private String conversationId;
    private Integer contentRevision;
    private Long materialTaskId;
    private String status;
    private Integer score;
    private String summary;
    private String previewFormat;
    private String previewPlanJson;
    private String errorMessage;
    private Long createdBy;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
