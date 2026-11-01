package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.aimedia.pojo.vo.resvo.PresignUploadRes;
import com.yanque.common.utils.TosPresignService;
import com.yanque.aimedia.pojo.vo.reqvo.ContentPreviewAssetPresignReq;
import com.yanque.aimedia.service.AiContentPreviewTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 仅供 YanQue-AI 成品渲染器上传最终文件；任务必须真实存在且正在处理。 */
@RestController
@RequestMapping("/internal/ai/content-preview-assets")
public class InternalContentPreviewAssetController {
    private final AiContentPreviewTaskService taskService;
    private final TosPresignService tosPresignService;

    public InternalContentPreviewAssetController(AiContentPreviewTaskService taskService,
                                                 TosPresignService tosPresignService) {
        this.taskService = taskService;
        this.tosPresignService = tosPresignService;
    }

    @PostMapping("/presign")
    public ApiResponse<PresignUploadRes> presign(@Valid @RequestBody ContentPreviewAssetPresignReq req) {
        taskService.requireProcessing(req.getPreviewTaskId());
        String suffix = "video/mp4".equals(req.getContentType()) ? ".mp4" : ".png";
        String key = "content-previews/" + req.getPreviewTaskId() + "/"
                + req.getSequenceNo() + "-" + UUID.randomUUID() + suffix;
        return ApiResponse.success(tosPresignService.presignUpload(key));
    }
}
