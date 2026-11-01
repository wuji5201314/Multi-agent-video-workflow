package com.yanque.aimedia.pojo.vo.resvo;

import lombok.Data;

/** 前端播放音频所需的音频信息和临时地址。 */
@Data
public class AiMediaAudioRes {
    /** 音频记录 ID。 */
    private Long id;
    /** 来源原始视频 ID。 */
    private Long videoId;
    /** 音频文件格式。 */
    private String fileFormat;
    /** 音频文件大小，单位字节。 */
    private Long fileSize;
    /** 音频时长，单位毫秒。 */
    private Long durationMs;
    /** 音频采样率。 */
    private Integer sampleRate;
    /** 音频声道数。 */
    private Integer channels;
    /** TOS 短时有效下载地址，数据库不保存该字段。 */
    private String previewUrl;
}
