package com.yanque.aimedia.pojo.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 素材库列表查询行，在素材数据上补充来源视频信息。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AiMediaVideoClipListRow extends AiMediaVideoClipEntity {
    private String sourceVideoTitle;
    private String sourceVideoFileName;
}
