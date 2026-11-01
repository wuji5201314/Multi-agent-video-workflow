package com.yanque.aimedia.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 人工审核通过后异步渲染的抖音成品任务。 */
@Data
public class AiContentPreviewTaskEntity {
    private Long id;
    private Long reviewTaskId;
    private String conversationId;
    private Integer contentRevision;
    private String format;
    private String status;
    private String previewPlanJson;
    private String coverObjectKey;
    private Long coverFileSize;
    private String videoObjectKey;
    private Long videoFileSize;
    private Long durationMs;
    private String errorMessage;
    private Long createdBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
