package com.yanque.aimedia.pojo.vo.resvo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 选题 Agent 可引用的一条真实抖音视频。 */
public record DouyinHotspotItemRes(
        @JsonProperty("video_id") String videoId,
        String title,
        @JsonProperty("video_url") String videoUrl,
        @JsonProperty("like_count") Long likeCount,
        @JsonProperty("like_increase") Long likeIncrease) {
}
