package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.aimedia.pojo.vo.reqvo.DouyinKeywordCollectReq;
import com.yanque.aimedia.pojo.vo.resvo.DouyinCollectRunRes;
import com.yanque.aimedia.service.DouyinCollectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 抖音热点采集任务的手动触发接口。 */
@RestController
@RequestMapping("/api/ai/douyin-collection")
public class DouyinCollectionController {
    /** 编排采集、视频去重和互动快照落库。 */
    private final DouyinCollectionService collectionService;

    public DouyinCollectionController(DouyinCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    /**
     * 按数据库已启用的目标同步执行一轮采集。
     *
     * 该接口不接收参数，采集内容完全由 ai_douyin_collect_target 中 enabled=1 的目标决定。
     */
    @PostMapping("/run")
    public ApiResponse<DouyinCollectRunRes> run() {
        return ApiResponse.success(collectionService.run());
    }

    /**
     * 按调用方本次提交的关键词同步执行一轮采集。
     *
     * 与 /run 的区别：不读取 ai_douyin_collect_target，直接使用请求体中的关键词，
     * 因此输入框里的词会真正参与抖音检索。
     */
    @PostMapping("/run-keyword")
    public ApiResponse<DouyinCollectRunRes> runByKeyword(@Valid @RequestBody DouyinKeywordCollectReq request) {
        return ApiResponse.success(collectionService.runByKeyword(request.keyword(), request.limit()));
    }
}
