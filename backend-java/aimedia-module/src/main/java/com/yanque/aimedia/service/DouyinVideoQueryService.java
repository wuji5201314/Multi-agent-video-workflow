package com.yanque.aimedia.service;

import cn.hutool.core.util.StrUtil;
import com.yanque.common.pojo.PageResult;
import com.yanque.aimedia.mapper.DouyinVideoMapper;
import com.yanque.aimedia.pojo.entity.DouyinVideoListRow;
import com.yanque.aimedia.pojo.vo.reqvo.DouyinVideoPageReq;
import com.yanque.aimedia.pojo.vo.resvo.DouyinVideoRes;
import com.alibaba.fastjson2.JSON;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Comparator;

/** 查询抖音视频主数据，并计算最新两次互动快照之间的增长量。 */
@Service
public class DouyinVideoQueryService {
    /** 查询视频及其最近两次互动快照。 */
    private final DouyinVideoMapper videoMapper;

    public DouyinVideoQueryService(DouyinVideoMapper videoMapper) {
        this.videoMapper = videoMapper;
    }

    /** 按关键词分页查询抖音视频和热度变化。 */
    public PageResult<DouyinVideoRes> page(DouyinVideoPageReq req) {
        PageHelper.startPage(req.getPageNum(), req.getPageSize());
        List<DouyinVideoListRow> rows = videoMapper.selectPage(StrUtil.trim(req.getKeyword()));
        PageInfo<DouyinVideoListRow> pageInfo = new PageInfo<>(rows);
        List<DouyinVideoRes> records = rows.stream().map(this::toResponse).toList();
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getPageNum(), pageInfo.getPageSize(), records);
    }

    /** 查询最近若干天的已采集热点，并按点赞增量和点赞总量排序。 */
    public List<DouyinVideoRes> recentHotspots(int days, int limit) {
        DouyinVideoPageReq req = new DouyinVideoPageReq();
        req.setPageNum(1);
        req.setPageSize(100);
        LocalDateTime earliest = LocalDateTime.now().minusDays(days);
        Comparator<DouyinVideoRes> rank = Comparator
                .comparing(DouyinVideoRes::likeIncrease, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DouyinVideoRes::likeCount, Comparator.nullsLast(Comparator.reverseOrder()));
        return page(req).getRecords().stream()
                .filter(video -> video.lastSeenAt() != null && !video.lastSeenAt().isBefore(earliest))
                .sorted(rank)
                .limit(limit)
                .toList();
    }

    /** 将数据库查询行转换为对外响应，并计算点赞增长量。 */
    private DouyinVideoRes toResponse(DouyinVideoListRow row) {
        List<String> matchedKeywords = StrUtil.isBlank(row.getMatchedKeywords())
                ? List.of() : JSON.parseArray(row.getMatchedKeywords(), String.class);
        return new DouyinVideoRes(
                row.getId(), row.getDouyinVideoId(), row.getVideoUrl(), row.getTitle(), row.getDescription(),
                row.getAuthorSecUserId(), row.getAuthorName(), row.getAuthorUrl(), row.getCoverUrl(),
                row.getDurationMs(), row.getPublishTime(), matchedKeywords, row.getFirstSeenAt(), row.getLastSeenAt(),
                row.getLikeCount(),
                increase(row.getLikeCount(), row.getPreviousLikeCount()),
                row.getMetricsCollectedAt());
    }

    /** 当前或上一快照缺失时不计算增量。 */
    private Long increase(Long current, Long previous) {
        return current == null || previous == null ? null : current - previous;
    }
}
