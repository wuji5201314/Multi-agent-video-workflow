package com.yanque.aimedia.handler;

import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.pojo.entity.AiMediaTranscriptEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoClipEntity;
import com.yanque.aimedia.service.AiMediaMaterialPythonClient;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import com.yanque.aimedia.service.AiMediaVideoClipService;
import org.springframework.stereotype.Component;

import java.util.List;

/** MATERIAL_SEGMENT_GENERATION：从整理后的对话中选择少量高价值视频片段。 */
@Component
public class MaterialSegmentGenerationStepHandler implements AiMediaProcessStepHandler {
    private final AiMediaProcessTaskService taskService;
    private final AiMediaMaterialPythonClient materialClient;
    private final AiMediaVideoClipService videoClipService;

    public MaterialSegmentGenerationStepHandler(AiMediaProcessTaskService taskService,
                                                AiMediaMaterialPythonClient materialClient,
                                                AiMediaVideoClipService videoClipService) {
        this.taskService = taskService;
        this.materialClient = materialClient;
        this.videoClipService = videoClipService;
    }

    @Override
    public MediaProcessStepCode stepCode() {
        return MediaProcessStepCode.MATERIAL_SEGMENT_GENERATION;
    }

    @Override
    public void handle(AiMediaProcessTaskEntity task, AiMediaProcessStepEntity step) {
        // 只读取 TRANSCRIPT_REFINEMENT 已保存的整理结果，不重新调用 ASR。
        AiMediaTranscriptEntity transcript = taskService.requireTranscript(task.getId());
        List<AiMediaMaterialPythonClient.MaterialSegment> segments = materialClient.generate(
                task.getVideoId(), transcript.getUtterancesJson());

        // Java 使用真实分句时间生成最终方案，大模型返回的只是分句范围和语义信息。
        List<AiMediaVideoClipEntity> clips = videoClipService.buildClips(task, transcript, segments);

        // 切片方案和步骤成功在同一事务中提交；当前节点不执行 FFmpeg。
        taskService.completeMaterialSegmentGeneration(task, step, clips);
    }
}
