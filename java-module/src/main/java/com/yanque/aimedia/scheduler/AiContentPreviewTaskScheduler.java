package com.yanque.aimedia.scheduler;

import com.yanque.aimedia.biz.AiContentPreviewTaskBiz;
import com.yanque.aimedia.pojo.entity.AiContentPreviewTaskEntity;
import com.yanque.aimedia.service.AiContentPreviewTaskService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 恢复服务重启遗留任务，并终止超过二十分钟的处理中任务。 */
@Component
public class AiContentPreviewTaskScheduler {
    private final AiContentPreviewTaskService service;
    private final AiContentPreviewTaskBiz biz;

    public AiContentPreviewTaskScheduler(AiContentPreviewTaskService service,
                                         AiContentPreviewTaskBiz biz) {
        this.service = service;
        this.biz = biz;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void recover() {
        service.failStale(LocalDateTime.now().minusMinutes(20));
        for (AiContentPreviewTaskEntity task : service.pending(20)) biz.dispatch(task.getId());
    }
}
