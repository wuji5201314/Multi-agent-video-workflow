package com.yanque.aimedia.handler;

import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoEntity;
import com.yanque.aimedia.service.AiMediaObjectStorageService;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import org.springframework.stereotype.Component;

/** PREPARE_VIDEO 步骤：检查原视频并验证 TOS 下载签名。 */
@Component
public class PrepareVideoStepHandler implements AiMediaProcessStepHandler {
    private final AiMediaProcessTaskService taskService;
    private final AiMediaObjectStorageService objectStorageService;

    public PrepareVideoStepHandler(AiMediaProcessTaskService taskService,
                                   AiMediaObjectStorageService objectStorageService) {
        this.taskService = taskService;
        this.objectStorageService = objectStorageService;
    }

    @Override
    public MediaProcessStepCode stepCode() {
        return MediaProcessStepCode.PREPARE_VIDEO;
    }

    @Override
    public void handle(AiMediaProcessTaskEntity task, AiMediaProcessStepEntity step) {
        // 原视频必须存在于业务表中。
        AiMediaVideoEntity video = taskService.requireVideo(task.getVideoId());

        // 使用 HeadObject 校验 TOS 文件大小，防止对象被删除或上传不完整。
        objectStorageService.verifyVideo(video.getObjectKey(), video.getFileSize());

        // URL 只用于验证签名配置，不入库；EXTRACT_AUDIO 执行时会重新签名。
        objectStorageService.createVideoInputUrl(video.getObjectKey());

        // 当前处理器执行完成后，将 PREPARE_VIDEO 标记为 SUCCESS。
        taskService.completeStep(step);
    }
}
