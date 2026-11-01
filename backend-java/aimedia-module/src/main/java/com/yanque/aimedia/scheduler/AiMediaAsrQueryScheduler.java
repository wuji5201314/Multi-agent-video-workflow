package com.yanque.aimedia.scheduler;

import com.yanque.aimedia.biz.AiMediaProcessBiz;
import com.yanque.aimedia.config.VolcengineAsrProperties;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

/** 定时领取已经达到查询间隔的 ASR_QUERY 步骤。 */
@Slf4j
@Component
public class AiMediaAsrQueryScheduler {
    private final AiMediaProcessTaskService taskService;
    private final AiMediaProcessBiz processBiz;
    private final VolcengineAsrProperties properties;
    private final Executor queryExecutor;

    public AiMediaAsrQueryScheduler(AiMediaProcessTaskService taskService,
                                    AiMediaProcessBiz processBiz,
                                    VolcengineAsrProperties properties,
                                    @Qualifier("aiMediaAsrQueryExecutor") Executor queryExecutor) {
        this.taskService = taskService;
        this.processBiz = processBiz;
        this.properties = properties;
        this.queryExecutor = queryExecutor;
    }

    /**
     * 每分钟扫描一次；步骤必须距离上次状态更新超过 queryIntervalSeconds 才会被领取。
     * updated_at 就是上次提交或查询时间，因此不需要额外的 next_query_at 字段。
     */
    @Scheduled(
            fixedDelayString = "${ai.media-processing.asr.scheduler-delay-millis:60000}",
            initialDelayString = "${ai.media-processing.asr.scheduler-delay-millis:60000}"
    )
    public void queryWaitingTasks() {
        LocalDateTime updatedBefore = LocalDateTime.now()
                .minusSeconds(properties.getQueryIntervalSeconds());
        List<AiMediaProcessStepEntity> steps = taskService.waitingAsrQueries(
                updatedBefore, properties.getQueryBatchSize());

        // 保留一条周期日志，方便确认 @Scheduled 已触发以及本轮查询到的任务数量。
        log.info("ASR waiting task scan completed, updatedBefore={}, waitingCount={}",
                updatedBefore, steps.size());

        for (AiMediaProcessStepEntity step : steps) {
            // SQL 只能按统一的最小间隔粗筛；退避过的步骤要按自己的 retry_count 再判一次
            // 是否到期。updated_at 由 ON UPDATE CURRENT_TIMESTAMP 维护，本身就是上次轮询
            // 或上次失败的时钟，不需要额外的 next_query_at 字段。
            int retryCount = step.getRetryCount();
            LocalDateTime nextQueryAt = step.getUpdatedAt()
                    .plusSeconds(properties.queryBackoffSeconds(retryCount));
            if (nextQueryAt.isAfter(LocalDateTime.now())) {
                continue;
            }

            // WAITING -> PROCESSING 是原子更新，只有抢占成功的实例才能发起查询。
            if (taskService.claimWaiting(step)) {
                log.info("ASR waiting step claimed, taskId={}, stepId={}, retryCount={}",
                        step.getTaskId(), step.getId(), retryCount);
                queryExecutor.execute(() -> processBiz.executeClaimedWaitingStep(step));
            }
        }
    }
}
