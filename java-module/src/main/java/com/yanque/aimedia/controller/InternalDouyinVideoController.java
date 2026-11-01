package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.common.pojo.PageResult;
import com.yanque.aimedia.pojo.vo.reqvo.DouyinVideoPageReq;
import com.yanque.aimedia.pojo.vo.resvo.DouyinVideoRes;
import com.yanque.aimedia.service.DouyinVideoQueryService;
import com.yanque.aimedia.service.DouyinHotspotDiscoveryService;
import com.yanque.aimedia.pojo.vo.reqvo.DouyinHotspotDiscoverReq;
import com.yanque.aimedia.pojo.vo.resvo.DouyinHotspotDiscoverRes;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Bear MCP 使用的抖音热点内部查询接口。
 *
 * 路径以 /internal 开头，只供服务间调用；返回结构与管理端分页接口保持一致，
 * 由 Bear 动态 Tool 继续完成时间范围过滤、排序和数量截断。
 */
@Validated
@RestController
@RequestMapping("/internal/ai/douyin-videos")
public class InternalDouyinVideoController {
    /** 查询视频主数据及最近两次点赞快照。 */
    private final DouyinVideoQueryService queryService;
    private final DouyinHotspotDiscoveryService discoveryService;

    public InternalDouyinVideoController(DouyinVideoQueryService queryService,
                                         DouyinHotspotDiscoveryService discoveryService) {
        this.queryService = queryService;
        this.discoveryService = discoveryService;
    }

    /**
     * 分页返回热点候选视频，供 query_douyin_hotspots 动态工具读取。
     *
     * @param req 页码、每页数量和可选关键词
     * @return 已采集视频及点赞变化
     */
    @GetMapping("/hotspots")
    public ApiResponse<PageResult<DouyinVideoRes>> hotspots(@Valid DouyinVideoPageReq req) {
        return ApiResponse.success(queryService.page(req));
    }

    /** 历史热点和关键词实时采集共用的 Bear 动态工具入口。 */
    @PostMapping("/discover")
    public ApiResponse<DouyinHotspotDiscoverRes> discover(
            @Valid @RequestBody DouyinHotspotDiscoverReq req) {
        return ApiResponse.success(discoveryService.discover(req));
    }
}
