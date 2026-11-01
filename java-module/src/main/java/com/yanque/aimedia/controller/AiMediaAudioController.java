package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaAudioRes;
import com.yanque.aimedia.service.AiMediaAudioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 原始视频产生的音频查询接口。 */
@RestController
@RequestMapping("/api/ai/media-videos/{videoId}/audio")
public class AiMediaAudioController {
    private final AiMediaAudioService audioService;

    public AiMediaAudioController(AiMediaAudioService audioService) {
        this.audioService = audioService;
    }

    /** 获取音频元数据和短时有效的 TOS 播放地址。 */
    @GetMapping
    public ApiResponse<AiMediaAudioRes> preview(@PathVariable Long videoId) {
        return ApiResponse.success(audioService.preview(videoId));
    }
}
