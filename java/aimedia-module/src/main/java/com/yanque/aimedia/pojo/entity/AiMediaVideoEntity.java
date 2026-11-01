package com.yanque.aimedia.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiMediaVideoEntity {
    private Long id;
    private String title;
    private String description;
    private String originalFileName;
    private String objectKey;
    private String fileType;
    private String contentType;
    private Long fileSize;
    private Long durationMs;
    private Integer width;
    private Integer height;
    /** 原始视频上传状态；当前登记成功的数据统一为 UPLOADED。 */
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 最新离线处理任务状态，不属于原始视频表字段。 */
    private String processStatus;
    /** 最新处理任务的失败节点，仅用于列表展示。 */
    private String failureStep;
    /** 最新处理任务的失败原因，仅用于列表展示。 */
    private String failureMessage;
}
