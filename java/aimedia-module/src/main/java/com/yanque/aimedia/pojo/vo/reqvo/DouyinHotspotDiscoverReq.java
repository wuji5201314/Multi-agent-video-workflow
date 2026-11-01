package com.yanque.aimedia.pojo.vo.reqvo;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Bear 热点工具的统一同步发现请求。 */
@Data
public class DouyinHotspotDiscoverReq {
    @Pattern(regexp = "COLLECTED_HOTSPOTS|REALTIME_KEYWORD", message = "选题来源不合法")
    @JsonProperty("source_mode")
    private String sourceMode = "COLLECTED_HOTSPOTS";
    private String keyword;
    @Min(1) @Max(90)
    private Integer days = 30;
    @Min(1) @Max(100)
    private Integer limit = 20;

    @AssertTrue(message = "实时查询关键词长度必须为2～50个字符")
    public boolean isKeywordValid() {
        if (!"REALTIME_KEYWORD".equals(sourceMode)) {
            return true;
        }
        int length = keyword == null ? 0 : keyword.trim().length();
        return length >= 2 && length <= 50;
    }
}
