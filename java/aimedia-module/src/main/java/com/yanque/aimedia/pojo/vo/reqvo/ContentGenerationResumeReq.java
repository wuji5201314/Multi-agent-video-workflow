package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 恢复内容生成工作流人工确认节点的请求参数。 */
@Data
public class ContentGenerationResumeReq {
    /** 操作类型：SELECT 确认选题，REGENERATE 按反馈重新生成。 */
    @NotBlank(message = "操作类型不能为空")
    private String action;

    /** SELECT 时提交的候选选题 ID。 */
    @Size(max = 64, message = "选题ID不能超过64个字符")
    private String topicId;

    /** REGENERATE 时提交的修改意见。 */
    @Size(max = 2000, message = "修改意见不能超过2000个字符")
    private String feedback;
}
