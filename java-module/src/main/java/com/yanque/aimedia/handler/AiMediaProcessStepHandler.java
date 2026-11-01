package com.yanque.aimedia.handler;

import com.yanque.aimedia.enums.MediaProcessStepCode;
import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;

/**
 * 视频处理步骤的统一接口。
 *
 * 每增加一种步骤，只需要新增一个实现类并交给 Spring 管理，
 * Biz 层不需要继续增加大量 if/else 或 switch 分支。
 */
public interface AiMediaProcessStepHandler {

    /** 当前实现类负责处理的步骤编码。 */
    MediaProcessStepCode stepCode();

    /** 执行当前步骤的具体业务。 */
    void handle(AiMediaProcessTaskEntity task, AiMediaProcessStepEntity step);
}
