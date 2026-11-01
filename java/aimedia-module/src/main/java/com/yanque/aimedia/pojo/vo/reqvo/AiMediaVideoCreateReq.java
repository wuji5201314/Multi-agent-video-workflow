package com.yanque.aimedia.pojo.vo.reqvo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiMediaVideoCreateReq {
    @NotBlank(message = "视频标题不能为空")
    @Size(max = 200, message = "视频标题不能超过200个字符")
    private String title;

    @Size(max = 1000, message = "视频说明不能超过1000个字符")
    private String description;

    @NotBlank(message = "原始文件名不能为空")
    @Size(max = 255, message = "原始文件名不能超过255个字符")
    private String originalFileName;

    @NotBlank(message = "对象Key不能为空")
    @Size(max = 500, message = "对象Key不能超过500个字符")
    private String objectKey;

    @NotNull(message = "文件大小不能为空")
    @Positive(message = "文件大小必须大于0")
    private Long fileSize;

    @Size(max = 100, message = "Content-Type不能超过100个字符")
    private String contentType;

    @PositiveOrZero(message = "视频时长不能小于0")
    private Long durationMs;

    @Positive(message = "视频宽度必须大于0")
    @Max(value = 32768, message = "视频宽度不合法")
    private Integer width;

    @Positive(message = "视频高度必须大于0")
    @Max(value = 32768, message = "视频高度不合法")
    private Integer height;
}
