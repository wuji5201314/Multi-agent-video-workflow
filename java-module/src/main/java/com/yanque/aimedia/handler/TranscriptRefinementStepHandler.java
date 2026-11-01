package com.yanque.aimedia.handler;

import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.pojo.entity.AiMediaTranscriptEntity;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import com.yanque.aimedia.service.AiMediaTranscriptPythonClient;
import org.springframework.stereotype.Component;

/** TRANSCRIPT_REFINEMENT：调用大模型整理说话人角色和转写文字。 */
@Component
public class TranscriptRefinementStepHandler implements AiMediaProcessStepHandler {
    private final AiMediaProcessTaskService taskService;
    private final AiMediaTranscriptPythonClient transcriptClient;

    public TranscriptRefinementStepHandler(AiMediaProcessTaskService taskService,
                                           AiMediaTranscriptPythonClient transcriptClient) {
        this.taskService = taskService;
        this.transcriptClient = transcriptClient;
    }

    @Override
    public MediaProcessStepCode stepCode() {
        return MediaProcessStepCode.TRANSCRIPT_REFINEMENT;
    }

    @Override
    public void handle(AiMediaProcessTaskEntity task, AiMediaProcessStepEntity step) {
        // ASR_QUERY 已经把精简分句保存到转写表，失败重试时可以直接复用。
        AiMediaTranscriptEntity transcript = taskService.requireTranscript(task.getId());
        AiMediaTranscriptPythonClient.RefinementResult result = transcriptClient.refine(
                task.getVideoId(), transcript.getUtterancesJson());

        // 大模型结果落库成功后，才结束步骤、任务和视频处理状态。
        taskService.completeTranscriptRefinement(
                task, step, result.fullText(), result.utterancesJson());
    }
}
