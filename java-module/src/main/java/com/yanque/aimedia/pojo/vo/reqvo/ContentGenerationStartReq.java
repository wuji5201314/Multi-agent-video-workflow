package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 启动内容生成工作流的请求参数。 */
@Data
public class ContentGenerationStartReq {
    /** 本轮生成的候选选题数量。 */
    @Min(value = 1, message = "候选选题数量不能小于1")
    @Max(value = 10, message = "候选选题数量不能大于10")
    private Integer candidateCount = 5;

    /** 使用历史热点，或按关键词实时采集。 */
    @Pattern(regexp = "COLLECTED_HOTSPOTS|REALTIME_KEYWORD", message = "选题来源不合法")
    private String sourceMode = "COLLECTED_HOTSPOTS";

    private String keyword;

    @AssertTrue(message = "实时查询关键词长度必须为2～50个字符")
    public boolean isKeywordValid() {
        if (!"REALTIME_KEYWORD".equals(sourceMode)) {
            return true;
        }
        int length = keyword == null ? 0 : keyword.trim().length();
        return length >= 2 && length <= 50;
    }
}
