package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.aimedia.pojo.vo.resvo.PresignUploadRes;
import com.yanque.common.utils.TosPresignService;
import com.yanque.aimedia.pojo.vo.reqvo.ContentMaterialImagePresignReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 仅供 YanQue-AI 保存生成图片使用的内部接口。 */
@RestController
@RequestMapping("/internal/ai/content-material-images")
public class InternalContentMaterialImageController {
    private final TosPresignService tosPresignService;

    public InternalContentMaterialImageController(TosPresignService tosPresignService) {
        this.tosPresignService = tosPresignService;
    }

    @PostMapping("/presign")
    public ApiResponse<PresignUploadRes> presign(
            @Valid @RequestBody ContentMaterialImagePresignReq req) {
        String objectKey = "content-material-images/" + req.getConversationId()
                + "/revision-" + req.getContentRevision()
                + "/" + req.getNeedId() + "-" + UUID.randomUUID() + ".png";
        return ApiResponse.success(tosPresignService.presignUpload(objectKey));
    }
}
