package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 首次创作或按反馈重新创作文字文章。 */
@Data
public class ContentGenerationContentReq {
    /** 重新创作时的修改意见，首次创作允许为空。 */
    @Size(max = 2000, message = "修改意见不能超过2000个字符")
    private String feedback;
}
