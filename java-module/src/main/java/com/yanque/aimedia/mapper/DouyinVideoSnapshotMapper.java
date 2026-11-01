package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.DouyinVideoSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

/** 抖音视频互动数据快照的数据访问接口。 */
@Mapper
public interface DouyinVideoSnapshotMapper {
    /** 新增一次采集快照；同一视频在同一窗口重复执行也保留历史记录。 */
    int insert(DouyinVideoSnapshotEntity snapshot);
}
