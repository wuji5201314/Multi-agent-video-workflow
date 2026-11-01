package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiContentMaterialTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiContentMaterialTaskMapper {
    int insert(AiContentMaterialTaskEntity task);
    AiContentMaterialTaskEntity selectById(@Param("id") Long id);
    AiContentMaterialTaskEntity selectByRevision(@Param("conversationId") String conversationId,
                                                 @Param("contentRevision") Integer contentRevision);
    List<AiContentMaterialTaskEntity> selectPending(@Param("limit") int limit);
    int claim(@Param("id") Long id);
    int markFinished(@Param("id") Long id, @Param("status") String status,
                     @Param("errorMessage") String errorMessage);
    int prepareRetry(@Param("id") Long id);
    int failStale(@Param("updatedBefore") LocalDateTime updatedBefore);
}
