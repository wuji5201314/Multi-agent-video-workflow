package com.yanque.aimedia.pojo.vo.resvo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 视频详情页展示的一条高价值素材片段。 */
@Data
public class AiMediaVideoClipRes {
    private Long id;
    /** 来源原视频，素材尚未生成独立文件时用于定位播放。 */
    private Long videoId;
    private Long taskId;
    private Integer segmentNo;
    /** 在原视频中的开始时间，单位毫秒。 */
    private Long startMs;
    /** 在原视频中的结束时间，单位毫秒。 */
    private Long endMs;
    private Long durationMs;
    private String title;
    private String summary;
    private String coreViewpoint;
    private String clipType;
    private String valueReason;
    private List<String> tags;
    private List<String> speakers;
    /** 对应时间范围内的整理后对话，便于人工确认切片内容。 */
    private String transcriptText;
    /** 步骤 70 生成真实切片后才会有值。 */
    private String objectKey;
    private Long fileSize;
    /** 素材库列表使用的来源信息；视频详情接口中可以为空。 */
    private String sourceVideoTitle;
    private String sourceVideoFileName;
    private LocalDateTime createdAt;
}
