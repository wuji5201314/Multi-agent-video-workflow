package com.yanque.aimedia.pojo.vo.resvo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一轮抖音采集的执行摘要。
 *
 * @param collectWindow 两小时快照窗口开始时间
 * @param targetCount 本轮启用的关键词和账号数量
 * @param returnedVideoCount Python 去重后返回的视频数量
 * @param newVideoCount 首次发现并新增的视频数量
 * @param updatedVideoCount 已存在并更新的视频数量
 * @param snapshotCount 本轮新增的互动快照数量
 * @param errors 目标级错误列表；单个目标失败不影响其他目标
 */
public record DouyinCollectRunRes(
        LocalDateTime collectWindow,
        int targetCount,
        int returnedVideoCount,
        int newVideoCount,
        int updatedVideoCount,
        int snapshotCount,
        List<String> errors) {
}
