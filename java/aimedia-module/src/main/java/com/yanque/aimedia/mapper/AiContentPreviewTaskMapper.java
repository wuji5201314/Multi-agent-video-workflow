package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiContentPreviewTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiContentPreviewTaskMapper {
    int insert(AiContentPreviewTaskEntity task);
    AiContentPreviewTaskEntity selectById(@Param("id") Long id);
    AiContentPreviewTaskEntity selectByReviewTaskId(@Param("reviewTaskId") Long reviewTaskId);
    List<AiContentPreviewTaskEntity> selectPending(@Param("limit") int limit);
    int claim(@Param("id") Long id);
    int markSuccess(AiContentPreviewTaskEntity task);
    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);
    int prepareRetry(@Param("id") Long id);
    int failStale(@Param("updatedBefore") LocalDateTime updatedBefore);
}
