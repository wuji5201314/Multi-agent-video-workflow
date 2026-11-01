package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiMediaProcessStepEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiMediaProcessStepMapper {
    /** 新增步骤实例并回填自增 ID。 */
    int insert(AiMediaProcessStepEntity step);

    /** 查询任务中顺序最靠前的待执行步骤。 */
    AiMediaProcessStepEntity selectNextPending(@Param("taskId") Long taskId);

    /** 查询任务的全部步骤，用于后续任务详情展示。 */
    List<AiMediaProcessStepEntity> selectByTaskId(@Param("taskId") Long taskId);

    /** 查询任务中的指定步骤。 */
    AiMediaProcessStepEntity selectByTaskIdAndCode(@Param("taskId") Long taskId,
                                                   @Param("stepCode") String stepCode);

    /** 查询达到轮询间隔的 ASR_QUERY 等待步骤。 */
    List<AiMediaProcessStepEntity> selectWaitingAsrQueries(@Param("updatedBefore") LocalDateTime updatedBefore,
                                                          @Param("limit") int limit);

    /** 查询指定步骤编码下已超过自身预算、仍停留在 PROCESSING 的失联步骤。 */
    List<AiMediaProcessStepEntity> selectStaleProcessing(@Param("stepCode") String stepCode,
                                                         @Param("staleBefore") LocalDateTime staleBefore,
                                                         @Param("limit") int limit);

    /** 原子地将待执行步骤改为执行中。 */
    int markProcessing(@Param("id") Long id);

    /** 将执行中的步骤标记为成功。 */
    int markSuccess(@Param("id") Long id);

    /** ASR_SUBMIT 成功后，将查询步骤从待执行改为等待外部结果。 */
    int markPendingAsWaiting(@Param("id") Long id);

    /** 火山仍在识别时，将本次查询恢复为等待状态。 */
    int markProcessingAsWaiting(@Param("id") Long id);

    /** ASR 查询瞬时失败：退回 WAITING 并累计一次重试。 */
    int markWaitingForRetry(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    /** 定时任务原子领取一个等待中的查询步骤。 */
    int claimWaiting(@Param("id") Long id);

    /** 将执行中的步骤标记为失败并保存错误原因。 */
    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    /** 人工重试时只恢复当前失败步骤，并累计重试次数。 */
    int prepareRetry(@Param("id") Long id);
}
