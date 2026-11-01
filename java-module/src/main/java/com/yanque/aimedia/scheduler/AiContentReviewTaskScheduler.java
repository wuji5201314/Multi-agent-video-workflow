package com.yanque.aimedia.scheduler;

import com.yanque.aimedia.biz.AiContentReviewTaskBiz;
import com.yanque.aimedia.pojo.entity.AiContentReviewTaskEntity;
import com.yanque.aimedia.service.AiContentReviewTaskService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 恢复待执行审核，并把失联十分钟的执行中任务标记失败。 */
@Component
public class AiContentReviewTaskScheduler {
    private final AiContentReviewTaskService taskService;
    private final AiContentReviewTaskBiz taskBiz;

    public AiContentReviewTaskScheduler(AiContentReviewTaskService taskService,
                                        AiContentReviewTaskBiz taskBiz) {
        this.taskService = taskService;
        this.taskBiz = taskBiz;
    }

    @Scheduled(fixedDelayString = "${ai.content-review.scan-delay-ms:30000}")
    public void recoverTasks() {
        // 先处理失联任务，再补投服务重启前已经写入数据库但尚未执行的 PENDING 任务。
        taskService.failStale(LocalDateTime.now().minusMinutes(10));
        for (AiContentReviewTaskEntity task : taskService.pending(20)) taskBiz.dispatch(task.getId());
    }
}
