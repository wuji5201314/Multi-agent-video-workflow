package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.common.pojo.PageResult;
import com.yanque.aimedia.pojo.vo.resvo.PresignDownloadRes;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaMaterialPageReq;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaVideoClipRes;
import com.yanque.aimedia.service.AiMediaVideoClipQueryService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 跨原视频的素材库查询接口。 */
@RestController
@Validated
@RequestMapping("/api/ai/media-materials")
public class AiMediaMaterialController {
    private final AiMediaVideoClipQueryService queryService;

    public AiMediaMaterialController(AiMediaVideoClipQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<PageResult<AiMediaVideoClipRes>> page(@Valid AiMediaMaterialPageReq req) {
        return ApiResponse.success(queryService.page(req));
    }

    /** 返回裁切后 MP4 的短时 TOS 播放地址。 */
    @GetMapping("/{id}/preview-url")
    public ApiResponse<PresignDownloadRes> previewUrl(@PathVariable Long id) {
        return ApiResponse.success(queryService.previewUrl(id));
    }
}
