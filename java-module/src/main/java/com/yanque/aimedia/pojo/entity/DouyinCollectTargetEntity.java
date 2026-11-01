package com.yanque.aimedia.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 抖音采集目标，对应一个搜索关键词或一个重点账号主页。 */
@Data
public class DouyinCollectTargetEntity {
    /** 主键 ID。 */
    private Long id;
    /** 目标类型：KEYWORD 关键词、ACCOUNT 账号主页。 */
    private String targetType;
    /** 关键词文本或抖音账号主页地址。 */
    private String targetValue;
    /** 供管理端展示的目标名称。 */
    private String targetName;
    /** 是否参与定时采集。 */
    private Boolean enabled;
    /** 最近一次未发生目标级错误的采集时间。 */
    private LocalDateTime lastCollectAt;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
