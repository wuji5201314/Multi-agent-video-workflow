package com.yanque.aimedia.pojo.entity;

import lombok.Data;
import java.math.BigDecimal;

/** 素材匹配任务中的一个视频切片或生成图片。 */
@Data
public class AiContentMaterialItemEntity {
    private Long id;
    private Long taskId;
    private String needId;
    private String articleExcerpt;
    private String assetType;
    private Long materialId;
    private Long videoId;
    private String clipType;
    private Long durationMs;
    private BigDecimal rerankScore;
    /** PROGRAMMATIC 表示代码绘图，QWEN_IMAGE 表示真实场景图。 */
    private String renderEngine;
    private String visualType;
    /** 完整结构化视觉方案，审核和单图重做都以它为依据。 */
    private String visualSpecJson;
    private String imagePrompt;
    private String matchReason;
    private String providerTaskId;
    private String imageObjectKey;
    private Long imageFileSize;
    private Integer imageWidth;
    private Integer imageHeight;
    private String imageError;
    private Integer visualReviewScore;
    private String visualReviewSummary;
    private Integer generationAttempt;
    private String regenerateFeedback;
}
