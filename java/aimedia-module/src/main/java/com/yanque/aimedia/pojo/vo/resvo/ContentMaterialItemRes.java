package com.yanque.aimedia.pojo.vo.resvo;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class ContentMaterialItemRes {
    private String needId;
    private String articleExcerpt;
    private String assetType;
    private Long materialId;
    private Long videoId;
    private String clipType;
    private Long durationMs;
    private BigDecimal rerankScore;
    private String renderEngine;
    private String visualType;
    private Object visualSpec;
    private String imagePrompt;
    private String matchReason;
    private String providerTaskId;
    private String imageObjectKey;
    private String imageUrl;
    private Long imageFileSize;
    private Integer imageWidth;
    private Integer imageHeight;
    private String imageError;
    private Integer visualReviewScore;
    private String visualReviewSummary;
    private Integer generationAttempt;
    private String regenerateFeedback;
}
