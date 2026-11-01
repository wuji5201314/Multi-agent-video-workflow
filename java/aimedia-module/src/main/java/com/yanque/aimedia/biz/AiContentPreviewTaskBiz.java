package com.yanque.aimedia.biz;

import com.yanque.aimedia.pojo.entity.AiContentPreviewTaskEntity;
import com.yanque.aimedia.pojo.vo.resvo.ContentPreviewTaskRes;
import com.yanque.aimedia.pojo.vo.resvo.ContentReviewTaskRes;
import com.yanque.aimedia.service.AiContentPreviewTaskService;
import com.yanque.aimedia.service.AiContentReviewTaskService;
import com.yanque.aimedia.service.ContentGenerationPythonClient;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/** 审核确认后的成品渲染异步编排层。 */
@Component
public class AiContentPreviewTaskBiz {
    private static final Logger log = LoggerFactory.getLogger(AiContentPreviewTaskBiz.class);
    private final AiContentPreviewTaskService taskService;
    private final AiContentReviewTaskService reviewService;
    private final ContentGenerationPythonClient pythonClient;
    private final Executor executor;

    public AiContentPreviewTaskBiz(AiContentPreviewTaskService taskService,
                                   AiContentReviewTaskService reviewService,
                                   ContentGenerationPythonClient pythonClient,
                                   @Qualifier("contentPreviewExecutor") Executor executor) {
        this.taskService = taskService;
        this.reviewService = reviewService;
        this.pythonClient = pythonClient;
        this.executor = executor;
    }

    public ContentReviewTaskRes approveAndStart(Long reviewTaskId, Long userId) {
        AiContentPreviewTaskEntity task = taskService.approveAndCreate(reviewTaskId, userId);
        if ("PENDING".equals(task.getStatus())) dispatch(task.getId());
        return reviewService.detail(reviewTaskId);
    }

    public void dispatch(Long taskId) { executor.execute(() -> execute(taskId)); }

    public void execute(Long taskId) {
        AiContentPreviewTaskEntity task = taskService.require(taskId);
        if (!taskService.claim(taskId)) return;
        try {
            JSONObject payload = taskService.buildRenderPayload(task);
            taskService.saveResult(taskId, pythonClient.renderPreview(payload));
        } catch (Exception exception) {
            log.error("Content preview render failed, taskId={}", taskId, exception);
            taskService.fail(taskId, exception);
        }
    }

    public ContentPreviewTaskRes retryAndStart(Long taskId) {
        taskService.prepareRetry(taskId);
        dispatch(taskId);
        return taskService.detail(taskId);
    }
}
