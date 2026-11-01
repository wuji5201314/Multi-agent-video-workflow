package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiContentPreviewItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiContentPreviewItemMapper {
    int insert(AiContentPreviewItemEntity item);
    int deleteByTaskId(@Param("taskId") Long taskId);
    List<AiContentPreviewItemEntity> selectByTaskId(@Param("taskId") Long taskId);
}
