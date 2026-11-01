package com.yanque.aimedia.scheduler;

import com.yanque.aimedia.config.AiMediaProcessingProperties;
import com.yanque.aimedia.config.AiMediaStepStalePolicy;
import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回收失联的执行中步骤。
 *
 * 视频处理跑在 JVM 内的线程池上，进程重启会把正在执行的步骤永久留在 PROCESSING：
 * 手动重试入口只认 FAILED，于是这条任务再也没人能把救回来。这里按各步骤自身的
 * 下游预算把它落成一次普通失败，让既有的手动重试重新可达。
 */
@Slf4j
@Component
public class AiMediaStaleStepScheduler {
    private static final String STALE_MESSAGE = "后台执行中断，请手动重试";

    private final AiMediaProcessTaskService taskService;
    private final AiMediaStepStalePolicy stalePolicy;
    private final AiMediaProcessingProperties properties;

    public AiMediaStaleStepScheduler(AiMediaProcessTaskService taskService,
                                     AiMediaStepStalePolicy stalePolicy,
                                     AiMediaProcessingProperties properties) {
        this.taskService = taskService;
        this.stalePolicy = stalePolicy;
        this.properties = properties;
    }

    /** 逐步骤编码各自扫描，才能套用不同的预算。 */
    @Scheduled(
            fixedDelayString = "${ai.media-processing.stale-sweep-delay-millis:120000}",
            initialDelayString = "${ai.media-processing.stale-sweep-delay-millis:120000}"
    )
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        int failed = 0;
        for (MediaProcessStepCode stepCode : MediaProcessStepCode.initialSteps()) {
            LocalDateTime staleBefore = stalePolicy.staleBefore(stepCode, now);
            List<AiMediaProcessStepEntity> staleSteps = taskService.staleProcessingSteps(
                    stepCode.name(), staleBefore, properties.getStaleBatchSize());
            for (AiMediaProcessStepEntity step : staleSteps) {
                if (taskService.failStaleStep(step, STALE_MESSAGE)) {
                    failed++;
                    log.warn("Stale media step reclaimed, taskId={}, stepId={}, stepCode={},"
                                    + " lastUpdatedAt={}, budget={}",
                            step.getTaskId(), step.getId(), step.getStepCode(),
                            step.getUpdatedAt(), stalePolicy.budget(stepCode));
                }
            }
        }
        if (failed > 0) {
            log.info("Stale media step sweep finished, reclaimedCount={}", failed);
        }
    }
}
