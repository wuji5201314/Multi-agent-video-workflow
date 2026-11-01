package com.yanque.aimedia.pojo.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 一篇文章一个版本对应的一次素材匹配后台任务。 */
@Data
public class AiContentMaterialTaskEntity {
    private Long id;
    private String conversationId;
    private Integer contentRevision;
    private String status;
    private String errorMessage;
    private Long createdBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
