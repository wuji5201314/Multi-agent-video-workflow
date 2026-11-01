package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.common.pojo.PageResult;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaTranscriptRes;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaTranscriptUtteranceRes;
import com.yanque.aimedia.service.AiMediaTranscriptService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;

/** 原始视频的 ASR 转写结果接口。 */
@RestController
@Validated
@RequestMapping("/api/ai/media-videos/{videoId}/transcript")
public class AiMediaTranscriptController {
    private final AiMediaTranscriptService transcriptService;

    public AiMediaTranscriptController(AiMediaTranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    /** 获取视频最新生成的转写全文。 */
    @GetMapping
    public ApiResponse<AiMediaTranscriptRes> detail(@PathVariable Long videoId) {
        return ApiResponse.success(transcriptService.detail(videoId));
    }

    /** 分页获取转写分句，不向浏览器传输庞大的逐字识别数据。 */
    @GetMapping("/utterances")
    public ApiResponse<PageResult<AiMediaTranscriptUtteranceRes>> utterancePage(
            @PathVariable Long videoId,
            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
            @RequestParam(defaultValue = "80") @Min(1) @Max(200) Integer pageSize) {
        return ApiResponse.success(transcriptService.utterancePage(videoId, pageNum, pageSize));
    }
}
