package com.yanque.aimedia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableScheduling
public class AiMediaProcessingConfig {
    /**
     * 视频转码是 CPU 和网络密集型任务，先限制为单并发。
     * 生产环境如果要横向扩容，可以再替换为 MQ + 独立 Worker。
     */
    @Bean("aiMediaProcessingExecutor")
    public Executor aiMediaProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-media-process-");
        executor.initialize();
        return executor;
    }

    /** ASR 查询不占用 FFmpeg 的单线程执行器，避免长视频阻塞查询结果。 */
    @Bean("aiMediaAsrQueryExecutor")
    public Executor aiMediaAsrQueryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-media-asr-query-");
        executor.initialize();
        return executor;
    }

    /** 素材匹配会等待视频召回和图片生成，使用独立线程池避免阻塞视频处理。 */
    @Bean("contentMaterialExecutor")
    public Executor contentMaterialExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("content-material-");
        executor.initialize();
        return executor;
    }

    /** 审核模型调用使用独立线程池，不占用图片生成任务。 */
    @Bean("contentReviewExecutor")
    public Executor contentReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("content-review-");
        executor.initialize();
        return executor;
    }

    /** FFmpeg 与 Playwright 成品渲染使用独立单线程，避免争抢素材生成线程。 */
    @Bean("contentPreviewExecutor")
    public Executor contentPreviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("content-preview-");
        executor.initialize();
        return executor;
    }
}
