package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.common.pojo.PageResult;
import com.yanque.aimedia.pojo.vo.reqvo.DouyinVideoPageReq;
import com.yanque.aimedia.pojo.vo.resvo.DouyinVideoRes;
import com.yanque.aimedia.service.DouyinVideoQueryService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 抖音热点视频的管理端查询接口。 */
@Validated
@RestController
@RequestMapping("/api/ai/douyin-videos")
public class DouyinVideoController {
    /** 查询视频主数据、最新互动量和相邻快照增量。 */
    private final DouyinVideoQueryService queryService;

    public DouyinVideoController(DouyinVideoQueryService queryService) {
        this.queryService = queryService;
    }

    /** 分页查询已经采集的抖音视频及其最新热度变化。 */
    @GetMapping
    public ApiResponse<PageResult<DouyinVideoRes>> page(@Valid DouyinVideoPageReq req) {
        return ApiResponse.success(queryService.page(req));
    }
}
