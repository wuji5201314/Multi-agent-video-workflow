package com.yanque.aimedia.pojo.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 视频分页查询行，在主数据上附加最近两次互动快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DouyinVideoListRow extends DouyinVideoEntity {
    /** 最新点赞数。 */
    private Long likeCount;
    /** 上一次快照的点赞数。 */
    private Long previousLikeCount;
    /** 最新互动数据的实际采集时间。 */
    private LocalDateTime metricsCollectedAt;
}
