package com.yanque.aimedia.handler;

import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoClipEntity;
import com.yanque.aimedia.service.AiMediaMaterialIndexPythonClient;
import com.yanque.aimedia.service.AiMediaProcessTaskService;
import org.springframework.stereotype.Component;

import java.util.List;

/** MATERIAL_INDEX：将已经生成的视频素材写入 Milvus 混合检索索引。 */
@Component
public class MaterialIndexStepHandler implements AiMediaProcessStepHandler {
    private final AiMediaProcessTaskService taskService;
    private final AiMediaMaterialIndexPythonClient indexClient;

    public MaterialIndexStepHandler(AiMediaProcessTaskService taskService,
                                    AiMediaMaterialIndexPythonClient indexClient) {
        this.taskService = taskService;
        this.indexClient = indexClient;
    }

    @Override
    public MediaProcessStepCode stepCode() {
        return MediaProcessStepCode.MATERIAL_INDEX;
    }

    @Override
    public void handle(AiMediaProcessTaskEntity task, AiMediaProcessStepEntity step) {
        // 一条素材就是一个检索单元；不再次拆成文档 chunk。
        List<AiMediaVideoClipEntity> clips = taskService.listVideoClips(task.getId());
        indexClient.index(task.getId(), clips);

        // 索引写入成功后完成最后一个步骤，整条处理任务才变成 SUCCESS。
        taskService.completeMaterialIndex(task, step);
    }
}
