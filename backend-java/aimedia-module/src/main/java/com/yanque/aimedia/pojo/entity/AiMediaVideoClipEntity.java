package com.yanque.aimedia.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 从原视频中识别出的高价值素材片段。 */
@Data
public class AiMediaVideoClipEntity {
    /** 素材片段主键。 */
    private Long id;
    /** 来源原视频 ID。 */
    private Long videoId;
    /** 来源离线处理任务 ID。 */
    private Long taskId;
    /** 来源转写记录 ID。 */
    private Long transcriptId;
    /** 当前任务内的片段序号。 */
    private Integer segmentNo;
    /** 片段开始时间，单位毫秒。 */
    private Long startMs;
    /** 片段结束时间，单位毫秒。 */
    private Long endMs;
    /** 片段时长，单位毫秒。 */
    private Long durationMs;
    /** 素材标题。 */
    private String title;
    /** 片段内容摘要。 */
    private String summary;
    /** 片段表达的核心观点。 */
    private String coreViewpoint;
    /** Q_AND_A、VIEWPOINT、KNOWLEDGE、STORY、CASE 或 TUTORIAL。 */
    private String clipType;
    /** 大模型判断该片段有价值的原因。 */
    private String valueReason;
    /** JSON 数组格式的标签。 */
    private String tagsJson;
    /** JSON 数组格式的说话人角色。 */
    private String speakersJson;
    /** 切片范围内按顺序拼接的整理后对话。 */
    private String transcriptText;
    /** FFmpeg 切片上传后的 TOS Key；未执行裁切时为空。 */
    private String objectKey;
    /** FFmpeg 生成的片段文件大小。 */
    private Long fileSize;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
