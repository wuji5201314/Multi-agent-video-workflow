package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 按输入框关键词实时采集抖音热点的请求体。
 *
 * 与定时任务的区别：定时任务读取 ai_douyin_collect_target 中 enabled=1 的目标，
 * 这里只使用调用方本次提交的关键词，不读取也不修改目标表。
 */
public record DouyinKeywordCollectReq(
        /** 本次采集使用的关键词，例如“Agent开发”。 */
        @NotBlank(message = "关键词不能为空")
        @Size(max = 100, message = "关键词长度不能超过 100")
        String keyword,
        /** 本次最多返回的视频数量；为空时使用配置中的默认值。 */
        Integer limit) {
}
