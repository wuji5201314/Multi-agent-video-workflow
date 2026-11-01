package com.yanque.aimedia.handler;

import com.yanque.aimedia.config.VolcengineAsrProperties;
import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.pojo.entity.AiMediaTranscriptEntity;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import com.yanque.aimedia.service.VolcengineAsrClient;
import org.springframework.stereotype.Component;

/** ASR_QUERY 步骤：查询火山任务，识别完成后保存完整文字。 */
@Component
public class AsrQueryStepHandler implements AiMediaProcessStepHandler {
    private final AiMediaProcessTaskService taskService;
    private final VolcengineAsrClient asrClient;
    private final VolcengineAsrProperties asrProperties;

    public AsrQueryStepHandler(AiMediaProcessTaskService taskService,
                               VolcengineAsrClient asrClient,
                               VolcengineAsrProperties asrProperties) {
        this.taskService = taskService;
        this.asrClient = asrClient;
        this.asrProperties = asrProperties;
    }

    @Override
    public MediaProcessStepCode stepCode() {
        return MediaProcessStepCode.ASR_QUERY;
    }

    @Override
    public void handle(AiMediaProcessTaskEntity task, AiMediaProcessStepEntity step) {
        AiMediaTranscriptEntity transcript = taskService.requireTranscript(task.getId());
        VolcengineAsrClient.QueryResult result;
        try {
            result = asrClient.query(transcript.getProviderTaskId());
        } catch (RuntimeException exception) {
            deferOrGiveUp(step, exception);
            return;
        }

        if (!result.completed()) {
            // “处理中”是正常结果：恢复 WAITING，由下一轮定时任务继续查询。
            taskService.keepAsrWaiting(step);
            return;
        }

        // 保存精简 ASR 分句并完成查询步骤，任务继续进入大模型整理节点。
        taskService.completeAsrQuery(task, step, result.text(), result.utterancesJson());
    }

    /**
     * 查询失败先退避重试，而不是直接把整条流水线打死。
     * <p>
     * 原实现里一次网络抖动就会让任务落到 FAILED，必须人工点重试。轮询本身对同一个
     * provider_task_id 幂等，所以这里不区分瞬时与永久故障：真·永久故障会在退避次数
     * 用尽后照常落 FAILED，只是多花几轮。
     */
    private void deferOrGiveUp(AiMediaProcessStepEntity step, RuntimeException exception) {
        int retried = step.getRetryCount();
        if (retried >= asrProperties.getQueryMaxRetries()) {
            throw exception;
        }
        String message = "ASR 查询失败，第 " + (retried + 1) + " 次退避重试："
                + exception.getMessage();
        taskService.deferAsrQuery(step, message);
    }
}
