package com.yanque.aimedia.pojo.entity;

import lombok.Data;

/** 代码规则或审核 Agent 发现的一条问题。 */
@Data
public class AiContentReviewIssueEntity {
    private Long id;
    private Long taskId;
    private String issueCode;
    private String category;
    private String severity;
    private String location;
    private String description;
    private String suggestion;
    private String source;
}
