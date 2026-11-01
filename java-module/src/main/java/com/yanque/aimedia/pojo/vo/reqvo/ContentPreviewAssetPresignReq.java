package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Python 成品渲染器申请最终图片或视频的 TOS 上传地址。 */
@Data
public class ContentPreviewAssetPresignReq {
    @NotNull @Min(1)
    private Long previewTaskId;
    @NotNull @Min(0) @Max(20)
    private Integer sequenceNo;
    @NotNull @Min(1) @Max(500L * 1024 * 1024)
    private Long fileSize;
    @NotBlank @Pattern(regexp = "^(image/png|video/mp4)$")
    private String contentType;
}
