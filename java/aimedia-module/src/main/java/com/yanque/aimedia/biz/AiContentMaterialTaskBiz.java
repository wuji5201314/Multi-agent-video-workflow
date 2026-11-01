package com.yanque.aimedia.biz;

import com.yanque.aimedia.pojo.entity.AiContentMaterialTaskEntity;
import com.yanque.aimedia.pojo.vo.resvo.ContentMaterialTaskCreateRes;
import com.yanque.aimedia.pojo.vo.resvo.ContentMaterialTaskRes;
import com.yanque.aimedia.service.AiContentMaterialTaskService;
import com.yanque.aimedia.service.AiContentReviewTaskService;
import com.yanque.aimedia.service.ContentGenerationPythonClient;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/** 文章素材任务的异步编排层。 */
@Component
public class AiContentMaterialTaskBiz {
    private static final Logger log = LoggerFactory.getLogger(AiContentMaterialTaskBiz.class);

    private final AiContentMaterialTaskService taskService;
    private final ContentGenerationPythonClient pythonClient;
    private final Executor executor;
    private final AiContentReviewTaskBiz reviewTaskBiz;
    private final AiContentReviewTaskService reviewTaskService;

    public AiContentMaterialTaskBiz(AiContentMaterialTaskService taskService,
                                    ContentGenerationPythonClient pythonClient,
                                    AiContentReviewTaskBiz reviewTaskBiz,
                                    AiContentReviewTaskService reviewTaskService,
                                    @Qualifier("contentMaterialExecutor") Executor executor) {
        this.taskService = taskService;
        this.pythonClient = pythonClient;
        this.reviewTaskBiz = reviewTaskBiz;
        this.reviewTaskService = reviewTaskService;
        this.executor = executor;
    }

    public ContentMaterialTaskCreateRes createAndStart(
            String conversationId, Integer contentRevision, Long userId) {
        ContentMaterialTaskCreateRes result = taskService.create(
                conversationId, contentRevision, userId);
        if ("PENDING".equals(result.getStatus())) {
            dispatch(result.getTaskId());
        }
        return result;
    }

    public ContentMaterialTaskCreateRes retryAndStart(Long taskId) {
        AiContentMaterialTaskEntity task = taskService.prepareRetry(taskId);
        dispatch(taskId);
        return new ContentMaterialTaskCreateRes(taskId, "PENDING");
    }

    /** 定时恢复扫描和 HTTP 创建都通过此入口提交，数据库 claim 保证只执行一次。 */
    public void dispatch(Long taskId) {
        executor.execute(() -> execute(taskId));
    }

    public void execute(Long taskId) {
        AiContentMaterialTaskEntity task = taskService.require(taskId);
        if (!taskService.claim(taskId)) {
            return;
        }
        try {
            JSONObject result = pythonClient.generateMaterials(
                    task.getConversationId(), task.getContentRevision());
            Long reviewTaskId = taskService.saveResult(task, result);
            if (reviewTaskId != null) reviewTaskBiz.dispatch(reviewTaskId);
        } catch (Exception exception) {
            log.error("Content material task failed, taskId={}, conversationId={}, revision={}",
                    taskId, task.getConversationId(), task.getContentRevision(), exception);
            taskService.fail(taskId, exception);
        }
    }

    /** 单图重做完成后作废旧审核，并自动重新执行第六步。 */
    public ContentMaterialTaskRes regenerateImage(Long taskId, String needId, String feedback) {
        taskService.validateImageRegenerate(taskId, needId);
        AiContentMaterialTaskEntity task = taskService.require(taskId);
        JSONObject result = pythonClient.regenerateMaterialImage(
                task.getConversationId(), needId, task.getContentRevision(), feedback);
        Long reviewTaskId = taskService.replaceGeneratedImage(task, needId, result);
        if (reviewTaskId != null) {
            reviewTaskService.resetAfterMaterialChange(reviewTaskId);
            reviewTaskBiz.dispatch(reviewTaskId);
        }
        return taskService.detail(taskId);
    }
}
