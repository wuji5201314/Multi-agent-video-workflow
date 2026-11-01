package com.yanque.aimedia.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** FFmpeg 从原始视频提取并上传到 TOS 的音频产物。 */
@Data
public class AiMediaAudioEntity {
    /** 音频记录主键。 */
    private Long id;
    /** 来源原始视频 ID。 */
    private Long videoId;
    /** 生成该音频的处理任务 ID。 */
    private Long taskId;
    /** 音频在 TOS 中的对象 Key。 */
    private String objectKey;
    /** 文件格式，当前固定为 mp3。 */
    private String fileFormat;
    /** MIME 类型，当前为 audio/mpeg。 */
    private String contentType;
    /** 音频文件大小，单位字节。 */
    private Long fileSize;
    /** 音频时长，单位毫秒。 */
    private Long durationMs;
    /** 采样率，当前为 16000Hz。 */
    private Integer sampleRate;
    /** 声道数，当前为单声道。 */
    private Integer channels;
    /** 数据创建时间。 */
    private LocalDateTime createdAt;
}
