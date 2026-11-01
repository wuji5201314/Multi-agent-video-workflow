package com.yanque.aimedia.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 某条抖音视频一次采集得到的互动数据快照。 */
@Data
public class DouyinVideoSnapshotEntity {
    /** 主键 ID。 */
    private Long id;
    /** 关联的 ai_douyin_video 主键 ID。 */
    private Long videoId;
    /** 归一化后的两小时窗口开始时间，用于对采集批次分组。 */
    private LocalDateTime collectWindow;
    /** 本次采集到的点赞数。 */
    private Long likeCount;
    /** 实际完成页面采集的时间。 */
    private LocalDateTime collectedAt;
}
