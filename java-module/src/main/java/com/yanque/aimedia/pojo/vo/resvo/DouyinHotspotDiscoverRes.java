package com.yanque.aimedia.pojo.vo.resvo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/** query_douyin_hotspots 动态工具的统一响应。 */
public record DouyinHotspotDiscoverRes(
        boolean success,
        @JsonProperty("source_mode") String sourceMode,
        String keyword,
        @JsonProperty("collected_at") LocalDateTime collectedAt,
        List<DouyinHotspotItemRes> hotspots) {
}
