package com.yanque.aimedia.pojo.vo.resvo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ContentReviewTaskRes {
    private Long taskId;
    private String conversationId;
    private Integer contentRevision;
    private Long materialTaskId;
    private String status;
    private Integer score;
    private String summary;
    private String previewFormat;
    private Object previewPlan;
    private Long previewTaskId;
    private String previewStatus;
    private String errorMessage;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private List<ContentReviewIssueRes> issues;
}
