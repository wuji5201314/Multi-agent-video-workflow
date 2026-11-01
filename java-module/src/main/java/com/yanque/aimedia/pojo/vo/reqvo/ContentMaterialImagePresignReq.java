package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Python 图片生成节点申请 TOS 上传地址。 */
@Data
public class ContentMaterialImagePresignReq {
    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9]{16,64}$")
    private String conversationId;
    @Min(1)
    @NotNull
    private Integer contentRevision;
    @NotBlank
    @Pattern(regexp = "^need-[1-5]$")
    private String needId;
    @Min(1)
    @Max(15 * 1024 * 1024)
    @NotNull
    private Long fileSize;
    @NotBlank
    @Pattern(regexp = "^image/png$")
    private String contentType;
}
