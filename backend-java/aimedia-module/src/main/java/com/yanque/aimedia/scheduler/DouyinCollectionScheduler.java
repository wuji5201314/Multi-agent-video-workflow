package com.yanque.aimedia.scheduler;

import com.yanque.aimedia.config.DouyinCollectionProperties;
import com.yanque.aimedia.pojo.vo.resvo.DouyinCollectRunRes;
import com.yanque.aimedia.service.DouyinCollectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 启动时和固定周期触发抖音热点采集，具体采集与落库交给业务服务处理。 */
@Slf4j
@Component
public class DouyinCollectionScheduler {
    /** 控制定时开关和采集限制。 */
    private final DouyinCollectionProperties properties;
    /** 执行完整采集链路。 */
    private final DouyinCollectionService collectionService;

    public DouyinCollectionScheduler(DouyinCollectionProperties properties,
                                     DouyinCollectionService collectionService) {
        this.properties = properties;
        this.collectionService = collectionService;
    }

    /** 应用完成启动后立即执行一轮，方便测试首次采集。 */
//    @EventListener(ApplicationReadyEvent.class)
    public void collectOnStartup() {
        collect();
    }

    /** 每 2 小时执行一次；默认关闭，可通过环境变量显式开启。 */
    @Scheduled(cron = "${ai.douyin-collection.cron:0 0 */2 * * ?}")
    public void collect() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            DouyinCollectRunRes result = collectionService.run();
            log.info("Scheduled Douyin collection finished: {}", result);
        } catch (Exception exception) {
            log.warn("Scheduled Douyin collection failed", exception);
        }
    }
}
