package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaVideoClipRes;
import com.yanque.aimedia.service.AiMediaVideoClipQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 原视频高价值素材片段查询接口。 */
@RestController
@RequestMapping("/api/ai/media-videos/{videoId}/clips")
public class AiMediaVideoClipController {
    private final AiMediaVideoClipQueryService queryService;

    public AiMediaVideoClipController(AiMediaVideoClipQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<List<AiMediaVideoClipRes>> list(@PathVariable Long videoId) {
        return ApiResponse.success(queryService.list(videoId));
    }
}
