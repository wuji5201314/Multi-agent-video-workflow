package com.yanque.aimedia.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 抖音热点采集任务的开关、数量限制和 HTTP 超时配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.douyin-collection")
public class DouyinCollectionProperties {
    /** 默认关闭，避免开发环境启动后自动打开浏览器。 */
    private boolean enabled = false;
    /** 单个关键词或账号本轮最多返回的视频数量。 */
    private int maxResultsPerTarget = 5;
    /** 一轮采集允许处理的视频总数上限。 */
    private int maxVideos = 10;
    /** Java 调用 YanQue-AI 抖音采集接口的超时时间，单位毫秒。 */
    private int httpTimeoutMillis = 300000;
}
