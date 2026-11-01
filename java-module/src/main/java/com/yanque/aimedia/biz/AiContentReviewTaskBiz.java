package com.yanque.aimedia.biz;

import com.yanque.aimedia.pojo.entity.AiContentReviewTaskEntity;
import com.yanque.aimedia.pojo.vo.resvo.ContentReviewTaskRes;
import com.yanque.aimedia.service.AiContentReviewTaskService;
import com.yanque.aimedia.service.ContentGenerationPythonClient;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/** 内容审核任务的异步编排层。 */
@Component
public class AiContentReviewTaskBiz {
    private static final Logger log = LoggerFactory.getLogger(AiContentReviewTaskBiz.class);
    private final AiContentReviewTaskService taskService;
    private final ContentGenerationPythonClient pythonClient;
    private final Executor executor;

    public AiContentReviewTaskBiz(AiContentReviewTaskService taskService,
                                  ContentGenerationPythonClient pythonClient,
                                  @Qualifier("contentReviewExecutor") Executor executor) {
        this.taskService = taskService;
        this.pythonClient = pythonClient;
        this.executor = executor;
    }

    /** 提交到独立审核线程池，素材生成线程不需要等待模型审核。 */
    public void dispatch(Long taskId) { executor.execute(() -> execute(taskId)); }

    /** 后台执行入口；claim 失败表示任务已被其他线程或实例接管。 */
    public void execute(Long taskId) {
        AiContentReviewTaskEntity task = taskService.require(taskId);
        if (!taskService.claim(taskId)) return;
        try {
            // Python 负责图状态和审核逻辑，Java 只负责任务编排与持久化。
            JSONObject result = pythonClient.generateReview(
                    task.getConversationId(), task.getContentRevision());
            taskService.saveResult(taskId, result);
        } catch (Exception exception) {
            log.error("Content review task failed, taskId={}, conversationId={}, revision={}",
                    taskId, task.getConversationId(), task.getContentRevision(), exception);
            taskService.fail(taskId, exception);
        }
    }

    public ContentReviewTaskRes retryAndStart(Long taskId) {
        // Service 会拒绝 REJECTED、PROCESSING 和 APPROVED，仅 FAILED 能回到 PENDING。
        taskService.prepareRetry(taskId);
        dispatch(taskId);
        return taskService.detail(taskId);
    }
}
