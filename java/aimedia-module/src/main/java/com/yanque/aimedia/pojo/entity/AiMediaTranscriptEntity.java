package com.yanque.aimedia.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 一次音频对应的 ASR 完整转写结果。 */
@Data
public class AiMediaTranscriptEntity {
    /** 转写记录主键。 */
    private Long id;
    /** 原始视频 ID。 */
    private Long videoId;
    /** 离线处理任务 ID。 */
    private Long taskId;
    /** 被识别的音频 ID。 */
    private Long audioId;
    /** ASR 服务商。 */
    private String provider;
    /** 火山 ASR 任务 ID，同时用于后续查询。 */
    private String providerTaskId;
    /** 识别语言。 */
    private String language;
    /** 完整转写文字。 */
    private String fullText;
    /** ASR 返回的分句、时间戳和说话人信息。 */
    private String utterancesJson;
    /** 识别音频时长，单位毫秒。 */
    private Long durationMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
