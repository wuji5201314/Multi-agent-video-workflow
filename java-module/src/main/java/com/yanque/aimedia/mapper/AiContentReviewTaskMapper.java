package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiContentReviewTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiContentReviewTaskMapper {
    int insert(AiContentReviewTaskEntity task);
    AiContentReviewTaskEntity selectById(@Param("id") Long id);
    AiContentReviewTaskEntity selectByRevision(@Param("conversationId") String conversationId,
                                               @Param("contentRevision") Integer contentRevision);
    AiContentReviewTaskEntity selectByMaterialTaskId(@Param("materialTaskId") Long materialTaskId);
    List<AiContentReviewTaskEntity> selectPending(@Param("limit") int limit);
    int claim(@Param("id") Long id);
    int markFinished(@Param("id") Long id, @Param("status") String status,
                     @Param("score") Integer score, @Param("summary") String summary,
                     @Param("errorMessage") String errorMessage,
                     @Param("previewFormat") String previewFormat,
                     @Param("previewPlanJson") String previewPlanJson);
    int prepareRetry(@Param("id") Long id);
    int approve(@Param("id") Long id, @Param("approvedBy") Long approvedBy);
    int failStale(@Param("updatedBefore") LocalDateTime updatedBefore);
    int resetAfterMaterialChange(@Param("id") Long id);
}
