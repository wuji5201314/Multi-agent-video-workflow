package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.AiContentReviewIssueEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiContentReviewIssueMapper {
    int insert(AiContentReviewIssueEntity issue);
    int deleteByTaskId(@Param("taskId") Long taskId);
    List<AiContentReviewIssueEntity> selectByTaskId(@Param("taskId") Long taskId);
}
