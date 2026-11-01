package com.yanque.aimedia.config;

import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.service.AiMediaMaterialIndexPythonClient;
import com.yanque.aimedia.service.AiMediaMaterialPythonClient;
import com.yanque.aimedia.service.AiMediaTranscriptPythonClient;
import com.yanque.aimedia.service.AiMediaVideoClipService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * 每个步骤「最长可能合法地停留在 PROCESSING」的预算。
 * <p>
 * 超时回收不能拍一个全局秒数：MATERIAL_CLIP_RENDER 会连续跑最多 3 段 FFmpeg，
 * 而每段自身允许跑到 7200 秒；ASR_QUERY 却应当在十几秒内回到 WAITING。用同一个
 * 阈值必然要么误杀正在转码的步骤，要么永远回收不到卡死的任务。
 * <p>
 * 因此这里从各步骤自己的下游预算（FFmpeg 超时、Python 请求超时、HTTP 超时）推导，
 * 再统一叠加一段余量覆盖进程内串行调度与重试的间隙。下游预算变化时这里自动跟随。
 */
@Component
public class AiMediaStepStalePolicy {

    private final AiMediaProcessingProperties processingProperties;
    private final VolcengineAsrProperties asrProperties;

    public AiMediaStepStalePolicy(AiMediaProcessingProperties processingProperties,
                                  VolcengineAsrProperties asrProperties) {
        this.processingProperties = processingProperties;
        this.asrProperties = asrProperties;
    }

    /** 步骤在依赖服务正常时最长可能占用执行线程的时间。 */
    public Duration budget(MediaProcessStepCode stepCode) {
        long seconds = switch (stepCode) {
            // 只查一次 TOS 对象是否存在，不搬运文件。
            case PREPARE_VIDEO -> 60;
            // 单条 FFmpeg 抽音轨。
            case EXTRACT_AUDIO -> processingProperties.getTimeoutSeconds();
            // 一次 15 秒超时的提交请求，外加为音频重新签名。
            case ASR_SUBMIT -> asrProperties.getHttpTimeoutMillis() / 1000 + 60;
            // 命中后立刻回到 WAITING，不应长时间停留在 PROCESSING。
            case ASR_QUERY -> asrProperties.getHttpTimeoutMillis() / 1000 + 60;
            // Python 侧分批整理，Java 同步等待全部批次。
            case TRANSCRIPT_REFINEMENT ->
                    AiMediaTranscriptPythonClient.REQUEST_TIMEOUT_MILLIS / 1000L;
            case MATERIAL_SEGMENT_GENERATION ->
                    AiMediaMaterialPythonClient.REQUEST_TIMEOUT_MILLIS / 1000L;
            // 最多 MAX_SEGMENTS 段串行转码，每段各自允许跑满 FFmpeg 超时。
            case MATERIAL_CLIP_RENDER ->
                    processingProperties.getTimeoutSeconds() * AiMediaVideoClipService.MAX_SEGMENTS;
            case MATERIAL_INDEX -> AiMediaMaterialIndexPythonClient.REQUEST_TIMEOUT_MILLIS / 1000L;
        };
        return Duration.ofSeconds(seconds).plusSeconds(processingProperties.getStaleGraceSeconds());
    }

    /** 超过该时刻仍在 PROCESSING 的同编码步骤即视为失联。 */
    public LocalDateTime staleBefore(MediaProcessStepCode stepCode, LocalDateTime now) {
        return now.minus(budget(stepCode));
    }
}
