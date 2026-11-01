package com.yanque.aimedia.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** FFmpeg 离线处理参数。 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.media-processing")
public class AiMediaProcessingProperties {
    /** FFmpeg 可执行文件名称或绝对路径。 */
    private String ffmpegPath = "ffmpeg";
    /** 音频临时目录；为空时使用操作系统临时目录。 */
    private String tempDir;
    /** 单次 FFmpeg 转换的最长执行时间。 */
    private long timeoutSeconds = 7200;
    /** 超时回收的扫描间隔；进程重启后卡死的步骤靠它恢复成可重试状态。 */
    private long staleSweepDelayMillis = 120_000;
    /** 叠加在各步骤自身预算之上的余量，覆盖排队与状态落库的间隙。 */
    private long staleGraceSeconds = 600;
    /** 单轮扫描每个步骤编码最多回收多少条。 */
    private int staleBatchSize = 50;
}
