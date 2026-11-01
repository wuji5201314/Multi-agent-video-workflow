package com.yanque.aimedia.pojo.vo.resvo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ContentMaterialTaskRes {
    private Long taskId;
    private String conversationId;
    private Integer contentRevision;
    private String status;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long reviewTaskId;
    private String reviewStatus;
    private List<ContentMaterialItemRes> materialMatches;
}
