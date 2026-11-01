package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.DouyinVideoEntity;
import com.yanque.aimedia.pojo.entity.DouyinVideoListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 抖音视频主数据的数据访问接口。 */
@Mapper
public interface DouyinVideoMapper {
    /** 根据抖音平台视频 ID 查询已有记录，用于业务去重。 */
    DouyinVideoEntity selectByDouyinVideoId(@Param("douyinVideoId") String douyinVideoId);

    /** 新增首次发现的视频。 */
    int insert(DouyinVideoEntity video);

    /** 更新重复发现的视频基础信息和最近发现时间。 */
    int update(DouyinVideoEntity video);

    /** 分页查询视频，并关联最近两次互动快照。 */
    List<DouyinVideoListRow> selectPage(@Param("keyword") String keyword);
}
