package com.yanque.aimedia.pojo.vo.resvo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiMediaVideoRes {
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
    /** 原始视频上传状态。 */
    private String uploadStatus;
    /** 最新离线处理任务状态；为空表示尚未创建处理任务。 */
    private String processStatus;
    /** 当前处理失败的步骤编码。 */
    private String failureStep;
    /** 当前处理失败的错误原因。 */
    private String failureMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
