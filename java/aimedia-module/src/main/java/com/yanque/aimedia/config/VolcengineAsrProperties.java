package com.yanque.aimedia.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 火山引擎大模型录音文件识别闲时版配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.media-processing.asr")
public class VolcengineAsrProperties {
    /** 闲时版 API 根地址。 */
    private String apiBaseUrl = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/idle";
    /** 控制台生成的 API Key。 */
    private String apiKey;
    /** 闲时版固定资源 ID。 */
    private String resourceId = "volc.bigasr.auc_idle";
    /** ASR 请求中的用户标识。 */
    private String userId = "yanque-media";
    /** HTTP 连接和读取超时时间。 */
    private int httpTimeoutMillis = 15_000;
    /** 提供给火山拉取音频的 TOS 地址有效期，默认 48 小时。 */
    private long audioUrlExpireSeconds = 48 * 60 * 60;
    /** 同一个 WAITING 步骤两次查询之间的最短间隔。 */
    private long queryIntervalSeconds = 5 * 60;
    /** 查询因瞬时故障退避时的最大次数，超过后步骤才落成 FAILED。 */
    private int queryMaxRetries = 5;
    /** 指数退避的封顶时间，避免长时间不可用时无限拉长间隔。 */
    private long queryBackoffMaxSeconds = 60 * 60;
    /** 定时任务每批最多领取的步骤数。 */
    private int queryBatchSize = 20;

    /**
     * 某次失败之后到下次允许查询的间隔。
     * <p>
     * 首次轮询用正常查询间隔；只有在查询真的抛错后 retry_count 才会增长，
     * 此时按 2 的幂退避，防止火山侧持续不可用把调度打成高频重试。
     */
    public long queryBackoffSeconds(int retryCount) {
        if (retryCount <= 0) {
            return queryIntervalSeconds;
        }
        long shifted = queryIntervalSeconds << Math.min(retryCount, 20);
        return Math.min(shifted <= 0 ? queryBackoffMaxSeconds : shifted, queryBackoffMaxSeconds);
    }
}
