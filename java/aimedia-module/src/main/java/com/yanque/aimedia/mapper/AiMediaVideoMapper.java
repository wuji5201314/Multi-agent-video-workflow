package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiMediaVideoEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMediaVideoMapper {
    List<AiMediaVideoEntity> selectPage(@Param("keyword") String keyword,
                                        @Param("processStatus") String processStatus);

    AiMediaVideoEntity selectById(@Param("id") Long id);

    AiMediaVideoEntity selectByObjectKey(@Param("objectKey") String objectKey);

    int insert(AiMediaVideoEntity video);

}
