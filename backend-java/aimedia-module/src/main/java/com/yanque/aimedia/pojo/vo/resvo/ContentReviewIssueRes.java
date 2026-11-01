package com.yanque.aimedia.pojo.vo.resvo;

import lombok.Data;

@Data
public class ContentReviewIssueRes {
    private String issueCode;
    private String category;
    private String severity;
    private String location;
    private String description;
    private String suggestion;
    private String source;
}
