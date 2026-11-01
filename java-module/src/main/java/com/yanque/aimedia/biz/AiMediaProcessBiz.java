package com.yanque.aimedia.biz;

import com.yanque.aimedia.common.AiMediaErrors;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.enums.MediaProcessStatus;
import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.handler.AiMediaProcessStepHandler;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaProcessTaskCreateRes;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 原始视频离线处理编排层。
 *
 * Biz 决定先做什么、后做什么；FFmpeg、TOS 和数据库细节分别由 Service 负责。
 */
@Component
public class AiMediaProcessBiz {
    private static final Logger log = LoggerFactory.getLogger(AiMediaProcessBiz.class);

    private final AiMediaProcessTaskService taskService;
    /** stepCode -> Handler 的 Java 内部注册表。 */
    private final Map<MediaProcessStepCode, AiMediaProcessStepHandler> handlerMap;
    private final Executor processingExecutor;

    public AiMediaProcessBiz(AiMediaProcessTaskService taskService,
                             List<AiMediaProcessStepHandler> handlers,
                             @Qualifier("aiMediaProcessingExecutor") Executor processingExecutor) {
        this.taskService = taskService;
        this.handlerMap = buildHandlerMap(handlers);
        this.processingExecutor = processingExecutor;
    }

    /**
     * 创建处理任务并提交到后台线程。
     *
     * @param videoId 原始视频 ID
     * @param userId  当前操作人 ID
     * @return 新任务 ID 和创建时的状态
     */
    public AiMediaProcessTaskCreateRes createAndStart(Long videoId, Long userId) {
        // 第一步只负责落库：创建任务记录和当前版本定义的步骤记录。
        // taskService.create 使用事务，方法返回时数据已经提交。
        AiMediaProcessTaskEntity task = taskService.create(videoId, userId);

        // 第二步提交异步线程。HTTP 接口不等待 FFmpeg 执行完成，立即返回 taskId。
        processingExecutor.execute(() -> execute(task.getId()));
        return new AiMediaProcessTaskCreateRes(task.getId(), task.getStatus());
    }

    /** 从 current_step 记录的失败节点重新开始，成功步骤不会重复执行。 */
    public AiMediaProcessTaskCreateRes retryAndStart(Long videoId) {
        AiMediaProcessTaskEntity task = taskService.prepareRetry(videoId);
        processingExecutor.execute(() -> execute(task.getId()));
        return new AiMediaProcessTaskCreateRes(task.getId(), MediaProcessStatus.PENDING.name());
    }

    /**
     * 执行指定任务中的待处理步骤。
     *
     * 当前线程一次只执行一个任务。每完成一个步骤，都重新从数据库查询下一条
     * PENDING 步骤，因此步骤顺序以数据库中的 step_order 为准。
     */
    public void execute(Long taskId) {
        // 先确认任务存在，再找到当前最靠前的待执行步骤。
        AiMediaProcessTaskEntity task = taskService.requireTask(taskId);
        AiMediaProcessStepEntity firstStep = taskService.nextPending(taskId);

        // claim 只把“任务表”状态从 PENDING 改成 PROCESSING，
        // 此时 firstStep 在“步骤表”中仍然是 PENDING。
        // 如果另一个线程已经领取该任务，本线程直接退出，避免重复转码。
        if (firstStep == null || !taskService.claim(taskId, firstStep.getStepCode())) {
            return;
        }

        executeSteps(task, firstStep);
    }

    /** 从指定步骤开始依次执行，前面已经成功的步骤不会重复执行。 */
    private void executeSteps(AiMediaProcessTaskEntity task, AiMediaProcessStepEntity firstStep) {
        AiMediaProcessStepEntity step = firstStep;
        while (step != null) {
            try {
                // 先把步骤改成 PROCESSING，并把任务 current_step 指向当前节点。
                taskService.startStep(task.getId(), step);

                // 根据 step_code 从注册表中找到对应实现类并执行。
                handler(step).handle(task, step);
            } catch (Exception exception) {
                log.error("AI media process failed, taskId={}, step={}",
                        task.getId(), step.getStepCode(), exception);

                // 当前步骤和任务均标记为 FAILED，保存错误原因后停止。
                // 此处不会自动重试，后续由运营人员点击手动重试。
                taskService.fail(task, step, exception);
                return;
            }

            // 当前步骤成功后，再按 step_order 查询下一条 PENDING 步骤。
            // 查询不到时循环自然结束，说明当前任务已经执行完成。
            step = taskService.nextPending(task.getId());
        }
    }

    /**
     * 执行已经由定时任务原子领取的 ASR_QUERY 步骤。
     * 该方法不再次领取任务，只负责调用对应 Handler 和统一记录失败状态。
     */
    public void executeClaimedWaitingStep(AiMediaProcessStepEntity step) {
        AiMediaProcessTaskEntity task = taskService.requireTask(step.getTaskId());
        try {
            handler(step).handle(task, step);
        } catch (Exception exception) {
            log.error("AI media waiting step failed, taskId={}, step={}",
                    task.getId(), step.getStepCode(), exception);
            taskService.fail(task, step, exception);
            return;
        }

        // ASR 尚未完成时，查询步骤会恢复为 WAITING，后续步骤会被顺序条件阻塞。
        // ASR 完成时，下一条 PENDING 步骤就是 TRANSCRIPT_REFINEMENT，直接继续执行。
        AiMediaProcessStepEntity nextStep = taskService.nextPending(task.getId());
        if (nextStep != null) {
            executeSteps(task, nextStep);
        }
    }

    /**
     * 把 Spring 注入的多个 Handler 实现整理成步骤注册表。
     * 如果两个实现声明了相同 stepCode，启动时直接失败，避免运行时随机选择。
     */
    private Map<MediaProcessStepCode, AiMediaProcessStepHandler> buildHandlerMap(
            List<AiMediaProcessStepHandler> handlers) {
        Map<MediaProcessStepCode, AiMediaProcessStepHandler> result =
                new EnumMap<>(MediaProcessStepCode.class);
        for (AiMediaProcessStepHandler handler : handlers) {
            AiMediaProcessStepHandler previous = result.put(handler.stepCode(), handler);
            if (previous != null) {
                throw new IllegalStateException("重复的视频处理器：" + handler.stepCode());
            }
        }
        return Map.copyOf(result);
    }

    /** 根据数据库中的 step_code 获取对应处理器。 */
    private AiMediaProcessStepHandler handler(AiMediaProcessStepEntity step) {
        try {
            MediaProcessStepCode stepCode = MediaProcessStepCode.valueOf(step.getStepCode());
            AiMediaProcessStepHandler handler = handlerMap.get(stepCode);
            if (handler == null) {
                throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_STEP_OPERATION_FAILED,
                        "未找到步骤处理器：" + step.getStepCode());
            }
            return handler;
        } catch (IllegalArgumentException exception) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_PROCESS_STEP_OPERATION_FAILED,
                    "未知的步骤编码：" + step.getStepCode());
        }
    }
}
