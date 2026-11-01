package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiContentMaterialItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiContentMaterialItemMapper {
    int insert(AiContentMaterialItemEntity item);
    int deleteByTaskId(@Param("taskId") Long taskId);
    List<AiContentMaterialItemEntity> selectByTaskId(@Param("taskId") Long taskId);
    AiContentMaterialItemEntity selectByTaskAndNeed(@Param("taskId") Long taskId,
                                                    @Param("needId") String needId);
    int updateGeneratedImage(AiContentMaterialItemEntity item);
}
