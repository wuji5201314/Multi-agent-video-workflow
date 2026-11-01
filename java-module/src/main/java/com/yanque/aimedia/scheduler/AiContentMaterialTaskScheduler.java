package com.yanque.aimedia.scheduler;

import com.yanque.aimedia.biz.AiContentMaterialTaskBiz;
import com.yanque.aimedia.pojo.entity.AiContentMaterialTaskEntity;
import com.yanque.aimedia.service.AiContentMaterialTaskService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 恢复服务重启时遗留的待执行任务，并终止失联的执行中任务。 */
@Component
public class AiContentMaterialTaskScheduler {
    private final AiContentMaterialTaskService taskService;
    private final AiContentMaterialTaskBiz taskBiz;

    public AiContentMaterialTaskScheduler(AiContentMaterialTaskService taskService,
                                          AiContentMaterialTaskBiz taskBiz) {
        this.taskService = taskService;
        this.taskBiz = taskBiz;
    }

    @Scheduled(fixedDelayString = "${ai.content-material.scan-delay-ms:30000}")
    public void recoverTasks() {
        taskService.failStale(LocalDateTime.now().minusMinutes(15));
        for (AiContentMaterialTaskEntity task : taskService.pending(20)) {
            taskBiz.dispatch(task.getId());
        }
    }
}
