package com.yanque.aimedia.controller;

import com.yanque.common.pojo.ApiResponse;
import com.yanque.common.pojo.PageResult;
import com.yanque.aimedia.pojo.vo.resvo.PresignDownloadRes;
import com.yanque.aimedia.pojo.vo.resvo.PresignUploadRes;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaVideoCreateReq;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaVideoPageReq;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaVideoPresignReq;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaVideoCreateRes;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaVideoRes;
import com.yanque.aimedia.service.AiMediaVideoService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/ai/media-videos")
public class AiMediaVideoController {
    private final AiMediaVideoService service;

    public AiMediaVideoController(AiMediaVideoService service) {
        this.service = service;
    }

    /** 分页查询原始视频。 */
    @GetMapping
    public ApiResponse<PageResult<AiMediaVideoRes>> page(@Valid AiMediaVideoPageReq req) {
        return ApiResponse.success(service.page(req));
    }

    /** 校验视频信息并生成限定目录下的 TOS PUT 预签名地址。 */
    @PostMapping("/presign-upload")
    public ApiResponse<PresignUploadRes> presignUpload(@Valid @RequestBody AiMediaVideoPresignReq req) {
        return ApiResponse.success(service.presignUpload(req));
    }

    /** 浏览器完成 TOS 上传后登记视频元数据。 */
    @PostMapping
    public ApiResponse<AiMediaVideoCreateRes> create(@Valid @RequestBody AiMediaVideoCreateReq req) {
        return ApiResponse.success(service.create(req));
    }

    /** 为在线播放生成短时有效的 TOS 下载地址。 */
    @GetMapping("/{id}/preview-url")
    public ApiResponse<PresignDownloadRes> previewUrl(@PathVariable Long id) {
        return ApiResponse.success(service.previewUrl(id));
    }
}
