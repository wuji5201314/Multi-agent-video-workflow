package com.yanque.aimedia.pojo.vo.resvo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ContentPreviewTaskRes {
    private Long taskId;
    private Long reviewTaskId;
    private String conversationId;
    private Integer contentRevision;
    private String format;
    private String status;
    private Object previewPlan;
    private String coverObjectKey;
    private String coverUrl;
    private String videoObjectKey;
    private String videoUrl;
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private List<ContentPreviewItemRes> items;
}
