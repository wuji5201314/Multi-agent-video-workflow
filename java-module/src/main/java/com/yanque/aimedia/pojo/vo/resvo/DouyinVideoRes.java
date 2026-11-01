package com.yanque.aimedia.pojo.vo.resvo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抖音视频分页响应，包含视频主数据、最新互动数据及相邻快照增量。
 *
 * @param id 数据库主键 ID
 * @param douyinVideoId 抖音平台视频 ID
 * @param videoUrl 视频公开页面地址
 * @param title 视频标题
 * @param description 视频描述
 * @param authorSecUserId 作者 sec_user_id
 * @param authorName 作者昵称
 * @param authorUrl 作者主页地址
 * @param coverUrl 视频封面地址
 * @param durationMs 视频时长，单位毫秒
 * @param publishTime 视频发布时间
 * @param matchedKeywords 命中过的采集关键词
 * @param firstSeenAt 首次发现时间
 * @param lastSeenAt 最近发现时间
 * @param likeCount 最新点赞数
 * @param likeIncrease 相比上一快照新增的点赞数
 * @param metricsCollectedAt 最新互动快照的实际采集时间
 */
public record DouyinVideoRes(
        Long id,
        String douyinVideoId,
        String videoUrl,
        String title,
        String description,
        String authorSecUserId,
        String authorName,
        String authorUrl,
        String coverUrl,
        Integer durationMs,
        LocalDateTime publishTime,
        List<String> matchedKeywords,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        Long likeCount,
        Long likeIncrease,
        LocalDateTime metricsCollectedAt) {
}
