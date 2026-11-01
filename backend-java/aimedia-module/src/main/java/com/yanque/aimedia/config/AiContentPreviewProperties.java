package com.yanque.aimedia.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 成品渲染（图片轮播 / 视频成片）开关。 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.content-preview")
public class AiContentPreviewProperties {
    /**
     * 是否允许生成视频成片。关闭时任何 VIDEO 方案都会被降级为图片轮播，
     * 保持“只做轮播”的历史行为；打开时沿用 YanQue-AI 审核方案给出的格式。
     */
    private boolean videoEnabled = true;
}
