package com.yanque.aimedia.mapper;

import com.yanque.aimedia.pojo.entity.DouyinCollectTargetEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 抖音关键词与重点账号采集目标的数据访问接口。 */
@Mapper
public interface DouyinCollectTargetMapper {
    /** 查询当前启用的全部关键词和账号目标。 */
    List<DouyinCollectTargetEntity> selectEnabled();

    /** 记录目标最近一次成功完成采集的时间。 */
    int updateLastCollectAt(@Param("id") Long id, @Param("lastCollectAt") LocalDateTime lastCollectAt);
}
