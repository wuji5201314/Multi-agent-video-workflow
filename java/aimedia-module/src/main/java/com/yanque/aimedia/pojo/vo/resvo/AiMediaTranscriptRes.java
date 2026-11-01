package com.yanque.aimedia.pojo.vo.resvo;

import lombok.Data;

import java.time.LocalDateTime;

/** 前端查看 ASR 转写全文所需的数据。 */
@Data
public class AiMediaTranscriptRes {
    /** 转写记录 ID。 */
    private Long id;
    /** 来源原始视频 ID。 */
    private Long videoId;
    /** 识别语言。 */
    private String language;
    /** ASR 返回的完整转写文字。 */
    private String fullText;
    /** 识别音频时长，单位毫秒。 */
    private Long durationMs;
    /** 转写结果最后更新时间。 */
    private LocalDateTime updatedAt;
}
