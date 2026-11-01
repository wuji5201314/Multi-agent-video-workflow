package com.yanque.aimedia.service;

import com.yanque.aimedia.pojo.vo.reqvo.DouyinHotspotDiscoverReq;
import com.yanque.aimedia.pojo.vo.resvo.DouyinHotspotDiscoverRes;
import com.yanque.aimedia.pojo.vo.resvo.DouyinHotspotItemRes;
import com.yanque.aimedia.pojo.vo.resvo.DouyinVideoRes;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 在历史热点查询和关键词实时采集之间做简单分流。 */
@Service
public class DouyinHotspotDiscoveryService {
    private final DouyinVideoQueryService queryService;
    private final DouyinCollectionService collectionService;

    public DouyinHotspotDiscoveryService(DouyinVideoQueryService queryService,
                                         DouyinCollectionService collectionService) {
        this.queryService = queryService;
        this.collectionService = collectionService;
    }

    public DouyinHotspotDiscoverRes discover(DouyinHotspotDiscoverReq req) {
        if ("REALTIME_KEYWORD".equals(req.getSourceMode())) {
            String keyword = req.getKeyword().trim();
            DouyinCollectionService.KeywordCollectResult result = collectionService.runKeyword(
                    keyword, req.getLimit());
            List<DouyinHotspotItemRes> hotspots = result.videos().stream()
                    .limit(req.getLimit())
                    .map(video -> new DouyinHotspotItemRes(
                            video.douyinVideoId(), video.title(), video.videoUrl(), video.likeCount(), null))
                    .toList();
            return new DouyinHotspotDiscoverRes(
                    true, req.getSourceMode(), keyword, result.collectedAt(), hotspots);
        }

        List<DouyinHotspotItemRes> hotspots = queryService.recentHotspots(req.getDays(), req.getLimit()).stream()
                .map(this::toHotspot)
                .toList();
        return new DouyinHotspotDiscoverRes(
                true, "COLLECTED_HOTSPOTS", null, LocalDateTime.now(), hotspots);
    }

    private DouyinHotspotItemRes toHotspot(DouyinVideoRes video) {
        return new DouyinHotspotItemRes(
                video.douyinVideoId(), video.title(), video.videoUrl(),
                video.likeCount(), video.likeIncrease());
    }
}
