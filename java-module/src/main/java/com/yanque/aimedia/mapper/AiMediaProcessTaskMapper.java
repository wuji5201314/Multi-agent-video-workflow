package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiMediaProcessTaskMapper {
    /** 新增任务并回填自增 ID。 */
    int insert(AiMediaProcessTaskEntity task);

    /** 根据主键查询任务。 */
    AiMediaProcessTaskEntity selectById(@Param("id") Long id);

    /** 查询原始视频最新创建的处理任务。 */
    AiMediaProcessTaskEntity selectLatestByVideoId(@Param("videoId") Long videoId);

    /** 只有一个线程能把 PENDING 任务领取为 PROCESSING。 */
    int claim(@Param("id") Long id, @Param("currentStep") String currentStep);

    /** 更新任务当前执行节点。 */
    int updateCurrentStep(@Param("id") Long id, @Param("currentStep") String currentStep);

    /** 将执行中的任务标记为成功。 */
    int markSuccess(@Param("id") Long id);

    /** 将执行中的任务标记为失败并保留失败节点。 */
    int markFailed(@Param("id") Long id, @Param("currentStep") String currentStep);

    /** 人工重试时将失败任务恢复为待领取状态。 */
    int prepareRetry(@Param("id") Long id);
}
