package com.yanque.aimedia.handler;

import com.yanque.aimedia.config.VolcengineAsrProperties;
import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.pojo.entity.AiMediaAudioEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.service.AiMediaObjectStorageService;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import com.yanque.aimedia.service.VolcengineAsrClient;
import org.springframework.stereotype.Component;

/** ASR_SUBMIT 步骤：提交火山闲时识别任务并保存任务 ID。 */
@Component
public class AsrSubmitStepHandler implements AiMediaProcessStepHandler {
    private final AiMediaProcessTaskService taskService;
    private final AiMediaObjectStorageService objectStorageService;
    private final VolcengineAsrClient asrClient;
    private final VolcengineAsrProperties asrProperties;

    public AsrSubmitStepHandler(AiMediaProcessTaskService taskService,
                                AiMediaObjectStorageService objectStorageService,
                                VolcengineAsrClient asrClient,
                                VolcengineAsrProperties asrProperties) {
        this.taskService = taskService;
        this.objectStorageService = objectStorageService;
        this.asrClient = asrClient;
        this.asrProperties = asrProperties;
    }

    @Override
    public MediaProcessStepCode stepCode() {
        return MediaProcessStepCode.ASR_SUBMIT;
    }

    @Override
    public void handle(AiMediaProcessTaskEntity task, AiMediaProcessStepEntity step) {
        AiMediaAudioEntity audio = taskService.requireAudio(task.getId());

        // 闲时任务可能排队较久，因此单独生成 48 小时的音频下载地址。
        String audioUrl = objectStorageService.createAsrAudioUrl(
                audio.getObjectKey(), asrProperties.getAudioUrlExpireSeconds());

        // 提交成功只返回任务 ID，不在当前线程等待识别结果。
        String providerTaskId = asrClient.submit(audioUrl);

        // 保存任务 ID，同时把 ASR_QUERY 改为 WAITING；当前执行线程随后自然结束。
        taskService.completeAsrSubmit(task, step, audio, providerTaskId);
    }
}
