package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.aimedia.common.AiMediaUserContext;
import com.yanque.aimedia.biz.AiMediaProcessBiz;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaProcessTaskCreateRes;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/media-process-tasks")
public class AiMediaProcessTaskController {
    private final AiMediaProcessBiz processBiz;

    public AiMediaProcessTaskController(AiMediaProcessBiz processBiz) {
        this.processBiz = processBiz;
    }

    /** 为一个已上传的原始视频创建任务，并异步开始提取音频。 */
    @PostMapping("/videos/{videoId}")
    public ApiResponse<AiMediaProcessTaskCreateRes> create(@PathVariable Long videoId) {
        return ApiResponse.success(processBiz.createAndStart(videoId, AiMediaUserContext.getUserId()));
    }

    /** 从最新任务记录的失败步骤继续执行。 */
    @PostMapping("/videos/{videoId}/retry")
    public ApiResponse<AiMediaProcessTaskCreateRes> retry(@PathVariable Long videoId) {
        return ApiResponse.success(processBiz.retryAndStart(videoId));
    }
}
