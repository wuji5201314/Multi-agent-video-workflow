package com.yanque.aimedia.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 抖音视频主数据，同一个 douyinVideoId 在库中只保留一条记录。 */
@Data
public class DouyinVideoEntity {
    /** 数据库主键 ID。 */
    private Long id;
    /** 抖音平台视频 ID，是视频去重的稳定业务键。 */
    private String douyinVideoId;
    /** 视频公开页面地址。 */
    private String videoUrl;
    /** 视频标题。 */
    private String title;
    /** 视频完整描述。 */
    private String description;
    /** 作者在抖音平台的 sec_user_id。 */
    private String authorSecUserId;
    /** 作者昵称。 */
    private String authorName;
    /** 作者公开主页地址。 */
    private String authorUrl;
    /** 视频封面地址，不下载到本地。 */
    private String coverUrl;
    /** 视频时长，单位毫秒。 */
    private Integer durationMs;
    /** 视频在抖音平台的发布时间。 */
    private LocalDateTime publishTime;
    /** 命中过的关键词 JSON 数组。 */
    private String matchedKeywords;
    /** 系统首次发现该视频的时间。 */
    private LocalDateTime firstSeenAt;
    /** 系统最近一次发现该视频的时间。 */
    private LocalDateTime lastSeenAt;
    /** 数据库记录创建时间。 */
    private LocalDateTime createdAt;
    /** 数据库记录更新时间。 */
    private LocalDateTime updatedAt;
}
