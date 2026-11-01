package com.yanque.aimedia.service;

import cn.hutool.core.util.StrUtil;
import com.yanque.aimedia.mapper.DouyinCollectTargetMapper;
import com.yanque.aimedia.mapper.DouyinVideoMapper;
import com.yanque.aimedia.mapper.DouyinVideoSnapshotMapper;
import com.yanque.aimedia.config.DouyinCollectionProperties;
import com.yanque.aimedia.pojo.entity.DouyinCollectTargetEntity;
import com.yanque.aimedia.pojo.entity.DouyinVideoEntity;
import com.yanque.aimedia.pojo.entity.DouyinVideoSnapshotEntity;
import com.yanque.aimedia.pojo.vo.resvo.DouyinCollectRunRes;
import com.yanque.common.exception.BusinessException;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 抖音热点采集主流程：读取启用目标、调用 Python、按视频 ID 去重并保存互动快照。
 * 单个目标失败不会回滚其他目标已经采集到的视频。
 */
@Slf4j
@Service
public class DouyinCollectionService {
    /** 单关键词采集数量的硬上限，避免误传超大值压垮抖音页面。 */
    private static final int MAX_RESULTS_PER_TARGET_CAP = 50;
    /** 读取关键词和重点账号配置。 */
    private final DouyinCollectTargetMapper targetMapper;
    /** 保存抖音视频主数据。 */
    private final DouyinVideoMapper videoMapper;
    /** 保存每次执行采集到的互动快照。 */
    private final DouyinVideoSnapshotMapper snapshotMapper;
    /** 调用 YanQue-AI 浏览器采集接口。 */
    private final DouyinPythonClient pythonClient;
    /** 采集开关、数量限制和超时配置。 */
    private final DouyinCollectionProperties properties;
    /** 防止启动触发、定时触发和手动触发在单实例内重叠执行。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DouyinCollectionService(DouyinCollectTargetMapper targetMapper,
                                   DouyinVideoMapper videoMapper,
                                   DouyinVideoSnapshotMapper snapshotMapper,
                                   DouyinPythonClient pythonClient,
                                   DouyinCollectionProperties properties) {
        this.targetMapper = targetMapper;
        this.videoMapper = videoMapper;
        this.snapshotMapper = snapshotMapper;
        this.pythonClient = pythonClient;
        this.properties = properties;
    }

    /**
     * 执行一轮完整采集。
     *
     * @return 本轮目标数、视频新增/更新数、快照数和目标级错误
     */
    public DouyinCollectRunRes run() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有抖音采集任务正在运行");
        }
        try {
            return doRun();
        } finally {
            running.set(false);
        }
    }

    /**
     * 按调用方本次提交的关键词同步采集一轮，不读取也不修改定时采集目标。
     *
     * 与 {@link #run()} 使用同一套去重、落库和快照逻辑，因此返回结构一致，
     * 前端可以用同一个结果提示处理。
     *
     * @param keyword 本次采集使用的关键词
     * @param limit   本次最多返回的视频数量；为空时使用配置默认值
     * @return 与本轮采集一致的结果摘要
     */
    public DouyinCollectRunRes runByKeyword(String keyword, Integer limit) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isEmpty()) {
            throw new BusinessException(400, "[DOUYIN_KEYWORD_REQUIRED] 采集关键词不能为空");
        }
        int perTarget = limit == null || limit <= 0
                ? properties.getMaxResultsPerTarget() : Math.min(limit, MAX_RESULTS_PER_TARGET_CAP);
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(409, "[DOUYIN_COLLECTION_CONFLICT] 已有抖音采集正在执行");
        }
        try {
            LocalDateTime collectedAt = LocalDateTime.now();
            LocalDateTime collectWindow = toCollectWindow(collectedAt);
            DouyinPythonClient.CollectResponse response =
                    pythonClient.collect(List.of(normalizedKeyword), List.of(), perTarget, perTarget);
            List<DouyinPythonClient.CollectedVideo> videos = deduplicate(response.videos());
            int newCount = 0;
            int updatedCount = 0;
            for (DouyinPythonClient.CollectedVideo collected : videos) {
                DouyinVideoEntity existing = videoMapper.selectByDouyinVideoId(collected.douyinVideoId());
                DouyinVideoEntity video = buildVideo(existing, collected, collectedAt);
                if (existing == null) {
                    videoMapper.insert(video);
                    newCount++;
                } else {
                    videoMapper.update(video);
                    updatedCount++;
                }
                snapshotMapper.insert(buildSnapshot(video.getId(), collected, collectWindow, collectedAt));
            }

            // 出现目标级错误时说明抖音侧未正常返回，只回传错误交给前端提示，
            // 不回写任何目标表的采集时间（本方法本来也不修改目标表）。
            List<String> errors = response.errors().stream()
                    .map(error -> error.targetType() + ":" + error.targetValue() + " - " + error.message())
                    .toList();
            log.info("Douyin keyword collection completed, keyword={}, window={}, videoCount={}, "
                            + "newCount={}, updatedCount={}, errorCount={}",
                    normalizedKeyword, collectWindow, videos.size(), newCount, updatedCount, errors.size());
            return new DouyinCollectRunRes(collectWindow, 1, videos.size(),
                    newCount, updatedCount, videos.size(), errors);
        } finally {
            running.set(false);
        }
    }

    /** 临时按一个关键词同步采集；不会读取定时采集目标。 */
    public KeywordCollectResult runKeyword(String keyword, int limit) {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(409, "[DOUYIN_COLLECTION_CONFLICT] 已有抖音采集正在执行");
        }
        try {
            LocalDateTime collectedAt = LocalDateTime.now();
            DouyinPythonClient.CollectResponse response = pythonClient.collect(
                    List.of(keyword), List.of(), limit, limit);
            if (!response.errors().isEmpty()) {
                String message = response.errors().stream().map(DouyinPythonClient.TargetError::message)
                        .reduce((left, right) -> left + "；" + right).orElse("实时采集失败");
                throw new BusinessException(502, "[DOUYIN_REALTIME_COLLECT_FAILED] " + message);
            }
            List<DouyinPythonClient.CollectedVideo> videos = deduplicate(response.videos());
            saveVideos(videos, collectedAt);
            return new KeywordCollectResult(collectedAt, videos);
        } finally {
            running.set(false);
        }
    }

    /** 保存实时查询返回的视频主数据和本轮互动快照。 */
    private void saveVideos(List<DouyinPythonClient.CollectedVideo> videos, LocalDateTime collectedAt) {
        LocalDateTime collectWindow = toCollectWindow(collectedAt);
        for (DouyinPythonClient.CollectedVideo collected : videos) {
            DouyinVideoEntity existing = videoMapper.selectByDouyinVideoId(collected.douyinVideoId());
            DouyinVideoEntity video = buildVideo(existing, collected, collectedAt);
            if (existing == null) {
                videoMapper.insert(video);
            } else {
                videoMapper.update(video);
            }
            snapshotMapper.insert(buildSnapshot(video.getId(), collected, collectWindow, collectedAt));
        }
    }

    /** 读取目标并完成 Python 调用、数据落库和成功目标时间更新。 */
    private DouyinCollectRunRes doRun() {
        List<DouyinCollectTargetEntity> targets = targetMapper.selectEnabled();
        List<String> keywords = targets.stream()
                .filter(target -> "KEYWORD".equals(target.getTargetType()))
                .map(DouyinCollectTargetEntity::getTargetValue).toList();
        List<String> accounts = targets.stream()
                .filter(target -> "ACCOUNT".equals(target.getTargetType()))
                .map(DouyinCollectTargetEntity::getTargetValue).toList();
        LocalDateTime collectedAt = LocalDateTime.now();
        LocalDateTime collectWindow = toCollectWindow(collectedAt);
        if (targets.isEmpty()) {
            return new DouyinCollectRunRes(collectWindow, 0, 0, 0, 0, 0, List.of());
        }

        DouyinPythonClient.CollectResponse response = pythonClient.collect(keywords, accounts);
        List<DouyinPythonClient.CollectedVideo> collectedVideos = deduplicate(response.videos());
        int newCount = 0;
        int updatedCount = 0;
        int snapshotCount = 0;
        for (DouyinPythonClient.CollectedVideo collected : collectedVideos) {
            DouyinVideoEntity existing = videoMapper.selectByDouyinVideoId(collected.douyinVideoId());
            DouyinVideoEntity video = buildVideo(existing, collected, collectedAt);
            if (existing == null) {
                videoMapper.insert(video);
                newCount++;
            } else {
                videoMapper.update(video);
                updatedCount++;
            }
            snapshotMapper.insert(buildSnapshot(video.getId(), collected, collectWindow, collectedAt));
            snapshotCount++;
        }

        // 发生目标级错误时，后续目标可能因为登录或风控而根本没有执行；整轮不更新时间，留待人工重试。
        if (response.errors().isEmpty()) {
            for (DouyinCollectTargetEntity target : targets) {
                targetMapper.updateLastCollectAt(target.getId(), collectedAt);
            }
        }
        List<String> errors = response.errors().stream()
                .map(error -> error.targetType() + ":" + error.targetValue() + " - " + error.message())
                .toList();
        log.info("Douyin collection completed, window={}, targetCount={}, videoCount={}, newCount={}, "
                        + "updatedCount={}, errorCount={}", collectWindow, targets.size(), collectedVideos.size(),
                newCount, updatedCount, errors.size());
        return new DouyinCollectRunRes(collectWindow, targets.size(), collectedVideos.size(),
                newCount, updatedCount, snapshotCount, errors);
    }

    /** 将任意时间归一化到其所在的偶数整点两小时窗口。 */
    static LocalDateTime toCollectWindow(LocalDateTime time) {
        return time.withHour(time.getHour() / 2 * 2).withMinute(0).withSecond(0).withNano(0);
    }

    /** 合并已有主数据和本次采集结果，避免空字段覆盖历史有效值。 */
    private DouyinVideoEntity buildVideo(DouyinVideoEntity existing,
                                          DouyinPythonClient.CollectedVideo collected,
                                          LocalDateTime collectedAt) {
        DouyinVideoEntity video = new DouyinVideoEntity();
        video.setId(existing == null ? null : existing.getId());
        video.setDouyinVideoId(collected.douyinVideoId());
        video.setVideoUrl(prefer(collected.videoUrl(), existing == null ? null : existing.getVideoUrl()));
        video.setTitle(prefer(collected.title(), existing == null ? null : existing.getTitle(), ""));
        video.setDescription(prefer(collected.description(), existing == null ? null : existing.getDescription()));
        video.setAuthorSecUserId(prefer(collected.authorSecUserId(), existing == null ? null : existing.getAuthorSecUserId()));
        video.setAuthorName(prefer(collected.authorName(), existing == null ? null : existing.getAuthorName()));
        video.setAuthorUrl(prefer(collected.authorUrl(), existing == null ? null : existing.getAuthorUrl()));
        video.setCoverUrl(prefer(collected.coverUrl(), existing == null ? null : existing.getCoverUrl()));
        video.setDurationMs(collected.durationMs() != null ? collected.durationMs()
                : existing == null ? null : existing.getDurationMs());
        video.setPublishTime(collected.publishTime() != null ? collected.publishTime()
                : existing == null ? null : existing.getPublishTime());
        video.setMatchedKeywords(mergeKeywords(existing == null ? null : existing.getMatchedKeywords(),
                collected.matchedKeywords()));
        video.setFirstSeenAt(existing == null ? collectedAt : existing.getFirstSeenAt());
        video.setLastSeenAt(collectedAt);
        return video;
    }

    /** 组装当前两小时窗口的视频互动快照。 */
    private DouyinVideoSnapshotEntity buildSnapshot(Long videoId,
                                                     DouyinPythonClient.CollectedVideo collected,
                                                     LocalDateTime collectWindow,
                                                     LocalDateTime collectedAt) {
        DouyinVideoSnapshotEntity snapshot = new DouyinVideoSnapshotEntity();
        snapshot.setVideoId(videoId);
        snapshot.setCollectWindow(collectWindow);
        snapshot.setLikeCount(collected.likeCount());
        snapshot.setCollectedAt(collectedAt);
        return snapshot;
    }

    /** 合并历史命中关键词和本次命中关键词，并序列化为 JSON 数组。 */
    private String mergeKeywords(String existingJson, List<String> collectedKeywords) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(existingJson)) {
            keywords.addAll(JSON.parseArray(existingJson, String.class));
        }
        if (collectedKeywords != null) {
            collectedKeywords.stream().filter(StrUtil::isNotBlank).forEach(keywords::add);
        }
        return JSON.toJSONString(keywords);
    }

    /** 优先使用本次非空值，否则保留历史值。 */
    private String prefer(String primary, String fallback) {
        return prefer(primary, fallback, null);
    }

    /** 优先使用本次非空值，再使用历史值，最后使用指定默认值。 */
    private String prefer(String primary, String fallback, String defaultValue) {
        if (StrUtil.isNotBlank(primary)) {
            return primary;
        }
        return StrUtil.isNotBlank(fallback) ? fallback : defaultValue;
    }

    /** 对 Python 返回结果再次按 douyinVideoId 去重，形成数据库写入边界。 */
    private List<DouyinPythonClient.CollectedVideo> deduplicate(
            List<DouyinPythonClient.CollectedVideo> source) {
        Map<String, DouyinPythonClient.CollectedVideo> videos = new LinkedHashMap<>();
        for (DouyinPythonClient.CollectedVideo video : source) {
            videos.merge(video.douyinVideoId(), video, this::mergeCollectedVideo);
        }
        return List.copyOf(videos.values());
    }

    /** 合并同一视频从不同关键词或账号目标返回的数据。 */
    private DouyinPythonClient.CollectedVideo mergeCollectedVideo(
            DouyinPythonClient.CollectedVideo first,
            DouyinPythonClient.CollectedVideo second) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (first.matchedKeywords() != null) {
            keywords.addAll(first.matchedKeywords());
        }
        if (second.matchedKeywords() != null) {
            keywords.addAll(second.matchedKeywords());
        }
        return new DouyinPythonClient.CollectedVideo(
                first.douyinVideoId(), prefer(second.videoUrl(), first.videoUrl()),
                prefer(second.title(), first.title()), prefer(second.description(), first.description()),
                prefer(second.authorSecUserId(), first.authorSecUserId()),
                prefer(second.authorName(), first.authorName()), prefer(second.authorUrl(), first.authorUrl()),
                prefer(second.coverUrl(), first.coverUrl()),
                second.durationMs() != null ? second.durationMs() : first.durationMs(),
                second.publishTime() != null ? second.publishTime() : first.publishTime(),
                List.copyOf(keywords), second.likeCount() != null ? second.likeCount() : first.likeCount());
    }

    /** 实时关键词采集的精确本轮结果。 */
    public record KeywordCollectResult(
            LocalDateTime collectedAt,
            List<DouyinPythonClient.CollectedVideo> videos) {
    }
}
