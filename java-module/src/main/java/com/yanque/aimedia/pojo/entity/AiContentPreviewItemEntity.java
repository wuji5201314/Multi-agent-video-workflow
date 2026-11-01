package com.yanque.aimedia.pojo.entity;

import lombok.Data;

/** IMAGE_CAROUSEL 中已经渲染并上传 TOS 的一张最终页面。 */
@Data
public class AiContentPreviewItemEntity {
    private Long id;
    private Long taskId;
    private Integer sequenceNo;
    private String needId;
    private String objectKey;
    private Long fileSize;
    private Integer width;
    private Integer height;
}
